package com.tetranova.prerex

import kotlin.math.*

class GroundTracker {

    // ===== PARAMETRI CONFIGURABILI =====
    // FIX SCALA ADC: questi parametri erano rimasti tarati sulla vecchia scala
    // normalizzata (0.001-1) di una precedente versione del firmware/protocollo, mentre
    // il resto della pipeline (maxAllowedGroundMagnitude, stableMovementThreshold) è già
    // stato ricalibrato sulla scala ADC reale (delta≈230, magnitude compensata≈392, vedi
    // commento su maxAllowedGroundMagnitude più sotto). Con i vecchi valori il budget di
    // correzione (maxDriftFromOrigin) saturava dopo ~40s a un totale di sole 0.08 unità,
    // trascurabile rispetto a un segnale che vive nell'ordine delle centinaia: il
    // GroundTracker smetteva silenziosamente di correggere il drift reale del terreno,
    // producendo instabilità dopo 2-3 minuti (il tempo tipico di assestamento termico
    // dell'hardware). Valori derivati PRESERVANDO IL RAPPORTO tra le vecchie costanti e
    // le due già ricalibrate empiricamente (maxAllowedGroundMagnitude: 0.02→500;
    // stableMovementThreshold: 0.002→1.5), non per stima arbitraria:
    //   - minNoiseThreshold: rapporto vecchio 0.001/0.02 = 5% di maxAllowedGroundMagnitude
    //     → 500 * 0.05 = 25
    //   - maxDriftFromOrigin: rapporto vecchio 0.08/0.002 = 40x stableMovementThreshold
    //     → 1.5 * 40 = 60
    //   - maxTrackingRatePerSecond: era numericamente identico a stableMovementThreshold
    //     nella vecchia scala (0.002 = 0.002) → mantenuto 1:1 → 1.5
    // alphaCorrection NON viene toccato: è un guadagno adimensionale che moltiplica
    // avgDelta (già nella nuova scala grande), quindi non soffre di un problema di unità;
    // va ritarato solo se la VELOCITÀ di risposta risulterà troppo lenta/veloce nei test.
    var alphaCorrection = 0.001f
    var minNoiseThreshold = 25f
    var stableMovementThreshold = 1.5f
    var stablePhaseThresholdDeg = 2.0f
    var stableMagnitudeThresholdPercent = 0.05f
    var groundLockDurationMs = 3000L
    var freezeAfterDetectionMs = 1200L
    var freezeAfterAnomalyMs = 1500L
    var maxTrackingRatePerSecond = 1.5f
    var maxDriftFromOrigin = 60f
    var verifyGroundTimeoutMs = 5000L

    // FIX (bug "falsi positivi inarrestabili dopo qualche minuto di auto-tracking"):
    // stableMovementThreshold (1.5) era una costante statica, scollegata dalla
    // sensibilità di rilevamento REALE, che invece cambia dinamicamente a ogni
    // calibrazione (vectorProcessor.enterDetectionThreshold, già passato qui come
    // parametro `threshold` di update() — vedi dynamicFreezeThreshold in SEARCH, che
    // lo usa già per lo stesso scopo). Da log reali: soglia di detection passata al
    // tracker ≈ 0,57 (Entra=1,029 / K=1,80), contro stableMovementThreshold = 1,5 —
    // quasi 3 volte più alta. Risultato: un segnale con escursione fino a 1,5 (quindi
    // ben più forte della soglia vera di detection) poteva restare "stabile" per
    // groundLockDurationMs e venire fuso nella baseline reale via GROUND_LOCK ->
    // applyCorrection() -> setExternalBaseline(). Se il coil sosta anche solo qualche
    // secondo vicino a un target debole, quel segnale veniva scambiato per "terreno",
    // la baseline si spostava verso il target, il terreno normale iniziava a sembrare
    // un delta, e il ciclo si autoalimentava — senza un tetto assoluto (vedi
    // originLeakRate) fino a diventare permanente.
    //
    // groundStabilityThresholdFraction lega la soglia di stabilità alla sensibilità
    // REALE corrente: un segnale può essere considerato "terreno" solo se resta ben
    // sotto (di default metà) la soglia che lo farebbe comunque rilevare come target.
    var groundStabilityThresholdFraction = 0.5f

    // FIX: prima bastava un solo campione stabile (verifySampleCount>0) per entrare in
    // GROUND_LOCK: fragile in presenza di rumore. Richiediamo un numero minimo di campioni.
    var minVerifySampleCount = 15

    // FIX: la correzione a fine GROUND_LOCK veniva applicata basandosi solo sul tempo
    // trascorso, senza garanzia di un numero minimo di campioni realmente accumulati
    // (rischio di media su pochissimi valori in caso di lag/GC/gap del sensore, o
    // addirittura divisione per zero se groundSampleCount restasse a 0).
    var minGroundSampleCountForCorrection = 200

    /**
     * Soglia massima di ampiezza del segnale per considerarlo "terreno".
     *
     * VALORE 500.0f:
     * ==============
     * Determinato dall'analisi dei dati grezzi del dispositivo (ESP32) che
     * presenta un segnale di fondo stabile con:
     *   - delta ≈ 230 (unità ADC)
     *   - phase ≈ 177.3°
     *
     * La baseline calcolata dal sistema dopo Air Balance + Pumping è:
     *   - I ≈ -162
     *   - Q ≈ -35
     *
     * Il sampleMagnitude che arriva al GroundTracker è:
     *   sampleMag = |filteredInput - baseline| ≈ 230 - (-162) ≈ 392
     *
     * Con il vecchio valore di 0.02f, TUTTI i campioni venivano scartati
     * perché sampleMag > 0.02, impedendo al tracker di entrare in
     * VERIFY_GROUND e GROUND_LOCK.
     *
     * 500.0f è un valore di compromesso che:
     *   - È maggiore del sampleMagnitude tipico (~392)
     *   - Dà margine per eventuali picchi del segnale
     *   - Permette al tracker di elaborare i campioni senza scartarli
     *
     * NOTA: Questo valore è specifico per l'hardware utilizzato
     * (ESP32 con bobina DD a basso rumore). Se si utilizza un
     * hardware diverso, potrebbe essere necessario ricalibrarlo.
     */
    var maxAllowedGroundMagnitude = 500.0f

    /** 🔥 Cooldown ridotto a 1500ms (allineato al freeze) per non rendere FREEZE ridondante */
    var detectionCooldownMs = 1500L

    // =============================================================
    // FIX (bug "falsi positivi inarrestabili", deadlock strutturale):
    // reanchorDriftOrigin() da sola sposta SOLO originRadial (il riferimento del
    // budget di deriva) sul valore ATTUALE di centerRadial — che è già il valore
    // corrotto che ha causato il falso positivo. Non corregge mai il centro vero.
    // Risultato osservato: il failsafe di VectorProcessor sblocca il flag di
    // detection per un istante, ma la baseline esterna resta quella sbagliata,
    // quindi il campione successivo la rileva di nuovo come "target" — e ogni
    // sblocco concede pure un budget di deriva NUOVO a partire dal punto
    // corrotto, quindi la situazione può solo peggiorare. Aggiungiamo tre
    // meccanismi indipendenti, tutti centrati su requestBaselineRecovery():
    //
    // 1) pumpingAnchorRadial: riferimento assoluto fissato al Pumping, MAI
    //    toccato da originLeakRate/reanchor. maxAbsoluteDriftFromPumping è un
    //    tetto duro applicato in applyCorrection() indipendente dal budget
    //    normale (che invece può scivolare via originLeakRate) — garantisce che
    //    centerRadial non si allontani mai indefinitamente dal terreno vero
    //    misurato al Pumping, qualunque cosa succeda al budget "mobile".
    // 2) lastGoodCenter*: snapshot del centro tracciato, aggiornato SOLO al
    //    termine di un ciclo di GROUND_LOCK con trackingQuality sopra soglia
    //    (quindi un punto realmente validato, non un valore in corso di
    //    corruzione). È il punto a cui torniamo quando il semplice re-ancoraggio
    //    del budget non basta a fermare il loop.
    // 3) recentRecoveryTimestampsMs: se i recuperi si ripetono troppo in fretta,
    //    non è un episodio isolato (rumore/target sottosoglia occasionale) ma un
    //    segno che la baseline stessa non è più affidabile. Dal 2° recupero in
    //    finestra si ripristina l'ultimo centro buono; dal 4° si smette di
    //    inseguire e si richiede una nuova calibrazione.
    // =============================================================
    private var pumpingAnchorRadial = 0f
    var maxAbsoluteDriftFromPumping = 180f

    private var lastGoodCenterRadial = 0f
    private var lastGoodCenterI = 0f
    private var lastGoodCenterQ = 0f
    private var hasLastGoodCenter = false
    var lastGoodQualityThreshold = 0.75f

    private val recentRecoveryTimestampsMs = ArrayDeque<Long>()
    // FIX (disallineamento "il contatore di recuperi non arriva mai a richiedere una
    // ricalibrazione"): il percorso più lento che chiama requestBaselineRecovery() è
    // il failsafe a 12s di VectorProcessor (autoTrackingStuckDetectionTimeoutMs). Ogni
    // volta che sblocca il flag di detection, il campione successivo lo rialza subito
    // (baseline ancora corrotta), generando un nuovo fronte di salita che RIAVVIA il
    // proprio timer da zero: quel percorso si ripete quindi a cadenza di ~12s, non una
    // tantum. Con una finestra di 30s rientrano al massimo 2-3 chiamate (30/12 = 2.5),
    // mai le 4 richieste da recoveryDisableCount: la richiesta di nuova calibrazione
    // non scattava mai per questo percorso, che restava bloccato in un loop di
    // "ripristina ultimo centro buono" ogni 12s all'infinito (recoveryEscalationCount=2
    // veniva raggiunto e mai superato). Serve una finestra che ecceda comodamente
    // (recoveryDisableCount - 1) * 12s = 36s anche nel caso peggiore in cui SOLO questo
    // percorso, il più lento, sia ad innescare i recuperi (il percorso via divergenza
    // G.ID, groundIdDivergenceSustainedMs=4000ms, è invece già abbastanza rapido da
    // rientrare comodamente anche nella vecchia finestra di 30s). 50s lascia margine
    // di sicurezza. Se in futuro si cambia autoTrackingStuckDetectionTimeoutMs in
    // VectorProcessor.kt, va ricontrollato che questo margine resti valido.
    var recoveryWindowMs = 50_000L
    var recoveryEscalationCount = 2
    var recoveryDisableCount = 4

    private var needsRecalibration = false
    fun needsRecalibration(): Boolean = needsRecalibration

    // ===== STATI =====
    private enum class State { SEARCH, DETECTION, FREEZE, VERIFY_GROUND, GROUND_LOCK }
    private var state = State.SEARCH
    private var stateEnterTimeMs = 0L

    // ===== STATO INTERNO =====
    private var axisDeg = 0f
    private var axisCos = 1f
    private var axisSin = 0f
    private var hasAxis = false

    private var centerI = 0f
    private var centerQ = 0f
    private var centerRadial = 0f
    private var originRadial = 0f
    private var driftLimitReached = false

    private var groundAccumulatedDelta = 0f
    private var groundSampleCount = 0
    private var lastRadialDelta = 0f
    private var previousRadialDeltaInLock = 0f
    private var verifyAccumulatedDelta = 0f
    private var verifySampleCount = 0
    private var verifyPhaseSum = 0f
    private var verifyMagnitudeSum = 0f

    private var lastDetectionTimeMs = 0L

    // =============================================================
    // FIX (bug "detection bloccata a tempo indefinito con bobina ferma a terra"):
    // update() fa return immediato quando isDetected==true, quindi l'INTERA pipeline
    // di correzione a moto richiesto (SEARCH -> VERIFY_GROUND -> GROUND_LOCK) non viene
    // mai raggiunta mentre la detection resta vera — esattamente il caso di una bobina
    // appoggiata a terra con baseline non corrispondente alla nuova posizione fisica.
    //
    // ARCHITETTURA A DUE ASSI (non un singolo timeout cieco):
    //   Asse 1 - persistenza della detection: detectionHoldStartMs, quanto tempo la
    //            detection resta ininterrottamente vera (fronte di salita).
    //   Asse 2 - stato fisico della bobina, dato dalla IMU (setImuStaticState, pilotato
    //            da GroundMotionDetector.isTrueStatic()): distingue TRUE STATIC (bobina
    //            appoggiata, LTA assoluto sotto soglia) da MICRO-TREMOR (bobina tenuta in
    //            mano, es. durante un pinpoint manuale — il tremore fisiologico rende
    //            impossibile per una mano avere LTA prossimo a zero) e da SWEEP (bobina in
    //            movimento reale, isCoilMovingValid(nowMs)).
    // Il riassorbimento è autorizzato SOLO quando entrambi gli assi lo confermano
    // (detection bloccata da tempo E IMU dice TRUE STATIC): mai durante SWEEP, mai durante
    // MICRO-TREMOR. Questo protegge esplicitamente il pinpoint manuale, che può
    // legittimamente durare 10-15s con la bobina tenuta ferma su un bersaglio reale — un
    // timeout cieco sulla sola detection lo tratterebbe come falso positivo e lo
    // inghiottirebbe nella baseline.
    //
    // Una volta autorizzato, NON si fa uno snap sul singolo campione corrente (rumoroso,
    // non verificato): accumulateStaticRecoverySample() applica lo stesso principio di
    // verifica a N campioni di VERIFY_GROUND/GROUND_LOCK, ma confrontando ogni nuovo
    // campione con la MEDIA IN COSTRUZIONE (non con centerRadial, il vecchio centro
    // corrotto): i gate di VERIFY_GROUND (maxAllowedGroundMagnitude, stabilità relativa al
    // vecchio centro) sono tarati per la piccola deriva incrementale e scarterebbero
    // sempre un salto di baseline di questa entità.
    // =============================================================
    private var detectionHoldStartMs = 0L
    private var isImuTrueStatic = false

    private var staticAccumI = 0f
    private var staticAccumQ = 0f
    private var staticSampleCount = 0

    var staticReabsorptionTimeoutMs = 3000L

    /** Da chiamare ad ogni campione con GroundMotionDetector.isTrueStatic(): distingue
     *  bobina appoggiata (LTA assoluto sotto soglia) da bobina tenuta in mano. */
    fun setImuStaticState(isStatic: Boolean) {
        isImuTrueStatic = isStatic
    }

    // ===== STATO MOVIMENTO =====
    private var isCoilMoving = false
    private var lastMotionUpdateTimeMs = 0L
    private var minMotionDurationForTrackingMs = 500L
    private var bypassMotionCheck = false

    // FIX: senza tolleranza, ogni breve stop rilevato dal sensore (es. inversione di
    // spazzolata che scende per un istante sotto la soglia OFF) faceva ripartire da zero
    // il timer dei minMotionDurationForTrackingMs, impedendo a spazzolate rapide o di
    // ampiezza corta con inversioni frequenti di superare mai il gate. Uno stop più breve
    // di questa tolleranza è considerato "rumore di inversione", non un vero arresto.
    private var lastStopTimeMs = 0L
    var motionGapToleranceMs = 400L

    // ============ Tolleranza di inversione adattiva ============
    // Invece di un valore fisso, la tolleranza si auto-calibra osservando quanto durano
    // le pause reali dell'utente tra un'inversione di spazzolata e la successiva ripresa
    // del movimento — misurate dal giroscopio stesso, non stimate a priori.
    private val recentGapDurationsMs = ArrayDeque<Long>()
    private var lastToleranceRecalcMs = 0L
    var toleranceRecalcIntervalMs = 8000L   // ricalcola non più spesso di ogni 8s
    var gapSampleWindowSize = 20             // quante pause recenti considerare
    var toleranceMarginMs = 150L             // margine di sicurezza sopra la pausa tipica osservata

    private var trackingQuality = 1f
    private var updateCount = 0L
    private var lastUpdateTimeMs = 0L


    // =============================================================
    // METODO PRINCIPALE
    // =============================================================

    fun update(
        sample: IQVector,
        isDetected: Boolean,
        isNoise: Boolean,
        threshold: Float
    ) {
        val nowMs = System.currentTimeMillis()
        if (!hasAxis) return

        val radialComponent = sample.i * axisCos + sample.q * axisSin
        val radialDelta = radialComponent - centerRadial
        lastRadialDelta = radialDelta
        val samplePhase = atan2(sample.q, sample.i).toDegrees()
        val sampleMagnitude = sample.magnitude

        // Se il segnale è rilevato, entra in DETECTION e termina il ciclo corrente
        if (isDetected) {
            if (detectionHoldStartMs == 0L) detectionHoldStartMs = nowMs

            // Autorizzazione a 2 assi: vedi blocco di commenti sopra. SOLO se la
            // detection resta bloccata da più di staticReabsorptionTimeoutMs E la IMU
            // conferma bobina fisicamente ferma (non MICRO-TREMOR, non SWEEP).
            if (isImuTrueStatic && (nowMs - detectionHoldStartMs) > staticReabsorptionTimeoutMs) {
                accumulateStaticRecoverySample(sample, threshold, nowMs)
            } else {
                resetStaticRecoveryAccumulator()
            }

            lastDetectionTimeMs = nowMs
            transitionTo(State.DETECTION, nowMs)
            return
        } else {
            detectionHoldStartMs = 0L
            resetStaticRecoveryAccumulator()
        }

        when (state) {
            State.SEARCH -> {
                // Cooldown
                if (nowMs - lastDetectionTimeMs < detectionCooldownMs) return
                if (sampleMagnitude > maxAllowedGroundMagnitude) return
                if (isCoilMovingValid(nowMs)) {
                    val dynamicFreezeThreshold = max(threshold * 3.0f, 0.05f)
                    if (abs(radialDelta) > dynamicFreezeThreshold) {
                        transitionTo(State.FREEZE, nowMs, freezeAfterAnomalyMs)
                        return
                    }
                }
                if (sampleMagnitude < maxAllowedGroundMagnitude) {
                    transitionTo(State.VERIFY_GROUND, nowMs)
                }
            }

            State.DETECTION -> {
                // Se siamo in DETECTION, significa che il campione precedente era detect,
                // ma ora non lo è (altrimenti saremmo usciti prima). Quindi la detection
                // è terminata → FREEZE.
                transitionTo(State.FREEZE, nowMs, freezeAfterDetectionMs)
            }

            State.FREEZE -> {
                if (nowMs - stateEnterTimeMs >= 0) {
                    transitionTo(State.SEARCH, nowMs)
                }
            }

            State.VERIFY_GROUND -> {
                if (!isCoilMovingValid(nowMs)) {
                    transitionTo(State.SEARCH, nowMs)
                    return
                }

                if (nowMs - lastDetectionTimeMs < detectionCooldownMs) {
                    transitionTo(State.SEARCH, nowMs)
                    return
                }

                if (nowMs - stateEnterTimeMs > verifyGroundTimeoutMs) {
                    transitionTo(State.SEARCH, nowMs)
                    return
                }

                // Nota: il controllo `if (isDetected)` non serve perché siamo già
                // nel ramo `!isDetected` (altrimenti saremmo usciti all'inizio).

                if (isNoise || sampleMagnitude < minNoiseThreshold) {
                    resetVerification()
                    return
                }

                if (sampleMagnitude > maxAllowedGroundMagnitude) {
                    resetVerification()
                    return
                }

                // FIX: vedi commento su groundStabilityThresholdFraction. La soglia di
                // stabilità non può mai superare una frazione della sensibilità di
                // detection REALE corrente, altrimenti un segnale più forte della soglia
                // di rilevamento vera potrebbe comunque passare come "terreno stabile".
                val dynamicStabilityLimit = min(stableMovementThreshold, threshold * groundStabilityThresholdFraction)
                val isStable = abs(radialDelta) < dynamicStabilityLimit
                val isPhaseStable = angleDiffAbsDeg(samplePhase, getCurrentPhase()) < stablePhaseThresholdDeg
                val isMagnitudeStable = abs(sampleMagnitude - getCurrentMagnitude()) / max(getCurrentMagnitude(), 0.001f) < stableMagnitudeThresholdPercent

                verifyAccumulatedDelta += radialDelta
                verifyPhaseSum += samplePhase
                verifyMagnitudeSum += sampleMagnitude
                verifySampleCount++

                if (isStable && isPhaseStable && isMagnitudeStable) {
                    if (verifySampleCount >= minVerifySampleCount) {
                        val avgVerifyDelta = verifyAccumulatedDelta / verifySampleCount
                        val verifiedSampleCount = verifySampleCount
                        transitionTo(State.GROUND_LOCK, nowMs)
                        // Il seed deve portare lo stesso peso (verifiedSampleCount campioni)
                        // della media che rappresenta, altrimenti la media finale in
                        // GROUND_LOCK risulta distorta (vedi bug di conteggio corretto).
                        // Nota: transitionTo() azzera già verifySampleCount tramite
                        // resetVerification(), quindi va catturato PRIMA della chiamata.
                        groundAccumulatedDelta = avgVerifyDelta * verifiedSampleCount
                        groundSampleCount = verifiedSampleCount
                    }
                } else {
                    resetVerification()
                }
            }

            State.GROUND_LOCK -> {
                if (!isCoilMovingValid(nowMs)) {
                    transitionTo(State.SEARCH, nowMs)
                    return
                }

                if (nowMs - lastDetectionTimeMs < detectionCooldownMs) {
                    transitionTo(State.SEARCH, nowMs)
                    return
                }

                // Nota: `if (isDetected)` non serve perché già fuori dal ramo detect.

                if (sampleMagnitude > maxAllowedGroundMagnitude) {
                    transitionTo(State.VERIFY_GROUND, nowMs)
                    return
                }

                val stepDelta = radialDelta - previousRadialDeltaInLock
                previousRadialDeltaInLock = radialDelta
                // FIX: stesso limite dinamico di VERIFY_GROUND (vedi commento su
                // groundStabilityThresholdFraction), applicato qui per coerenza — un
                // passo verso un segnale più forte della soglia di detection reale deve
                // far uscire dal lock e tornare a verificare, non essere tollerato solo
                // perché resta sotto la costante statica.
                val dynamicStepLimit = min(stableMovementThreshold, threshold * groundStabilityThresholdFraction) * 1.5f
                if (abs(stepDelta) > dynamicStepLimit) {
                    transitionTo(State.VERIFY_GROUND, nowMs)
                    return
                }

                groundAccumulatedDelta += radialDelta
                groundSampleCount++

                if (nowMs - stateEnterTimeMs >= groundLockDurationMs) {
                    if (groundSampleCount >= minGroundSampleCountForCorrection) {
                        val avgDelta = groundAccumulatedDelta / groundSampleCount
                        if (abs(avgDelta) > 0.0001f) {
                            applyCorrection(avgDelta, nowMs)
                        }
                        // Snapshot del centro come "buono" SOLO se il ciclo appena concluso
                        // ha mantenuto una qualità di tracking alta: è il punto a cui
                        // requestBaselineRecovery() torna quando il recupero "leggero" non
                        // basta a fermare un loop di falsi positivi.
                        if (trackingQuality > lastGoodQualityThreshold) {
                            lastGoodCenterRadial = centerRadial
                            lastGoodCenterI = centerI
                            lastGoodCenterQ = centerQ
                            hasLastGoodCenter = true
                        }
                    }
                    transitionTo(State.SEARCH, nowMs)
                }
            }
        }
    }

    // =============================================================
    // TRANSIZIONI E UTILITY
    // =============================================================

    private fun transitionTo(newState: State, nowMs: Long, freezeDurationMs: Long = 0L) {
        if (newState != state) {
            android.util.Log.d("GroundTracker", "🔄 $state → $newState (${System.currentTimeMillis()})")
        }

        when (state) {
            State.VERIFY_GROUND -> resetVerification()
            State.GROUND_LOCK -> {
                groundAccumulatedDelta = 0f
                groundSampleCount = 0
                previousRadialDeltaInLock = 0f
            }
            else -> {}
        }

        state = newState
        stateEnterTimeMs = nowMs + freezeDurationMs

        when (newState) {
            State.VERIFY_GROUND -> resetVerification()
            State.GROUND_LOCK -> {
                groundAccumulatedDelta = 0f
                groundSampleCount = 0
                previousRadialDeltaInLock = 0f
            }
            State.SEARCH -> {
                resetVerification()
                groundAccumulatedDelta = 0f
                groundSampleCount = 0
                previousRadialDeltaInLock = 0f
            }
            else -> {}
        }
    }

    // Velocità di scorrimento dell'origine quando il drift supera il budget
    // (maxDriftFromOrigin). Non è un parametro di scala ADC: è una FRAZIONE
    // adimensionale dell'eccesso di drift che l'origine recupera ad ogni ciclo di
    // correzione (ogni groundLockDurationMs). Un valore piccolo (0.1 = 10%) rende
    // l'inseguimento dell'origine molto più lento della soglia di rilevazione target,
    // così un bersaglio reale (transiente rapido, un solo ciclo) non riesce a spostare
    // l'origine in modo apprezzabile, mentre una deriva geologica genuina, che satura
    // il budget ciclo dopo ciclo per minuti, la fa scorrere gradualmente.
    var originLeakRate = 0.1f

    private fun applyCorrection(avgDelta: Float, nowMs: Long) {
        val rawStep = alphaCorrection * avgDelta
        val dtSec = if (lastUpdateTimeMs == 0L) 0.01f else ((nowMs - lastUpdateTimeMs) / 1000f).coerceIn(0f, 1f)
        lastUpdateTimeMs = nowMs
        val maxStepPerSecond = maxTrackingRatePerSecond * dtSec
        val limitedStep = rawStep.coerceIn(-maxStepPerSecond, maxStepPerSecond)

        val proposedRadial = centerRadial + limitedStep
        val proposedDrift = proposedRadial - originRadial

        // FIX STRUTTURALE (elimina il plateau permanente): la versione precedente, quando
        // |proposedDrift| superava maxDriftFromOrigin, PINNAVA il centro a
        // originRadial ± maxDriftFromOrigin per il resto della sessione: originRadial non
        // veniva mai più toccato se non da un reset esplicito (Pumping o
        // reanchorDriftOrigin() via failsafe). Il terreno reale, però, non è una costante:
        // durante una camminata prolungata la sua mineralizzazione cambia gradualmente e in
        // modo fisiologico, non anomalo. Bloccare l'origine per sempre significa che, una
        // volta esaurito il budget (che ora è correttamente in scala ADC ma resta comunque
        // un budget finito), il sistema smette di inseguire un terreno che continua invece
        // a muoversi — esattamente il sintomo "rilevazione bloccata sempre sullo stesso
        // valore dopo alcuni minuti" osservato sul campo.
        //
        // SOLUZIONE: quando il budget viene superato, invece di pinnare, facciamo scivolare
        // l'ORIGINE stessa (originLeakRate, tipicamente 10% dell'eccesso per ciclo) verso il
        // nuovo punto. L'origine insegue quindi il terreno con una costante di tempo molto
        // più lenta del ciclo di correzione stesso (ogni groundLockDurationMs, tipicamente
        // 3s): un bersaglio reale genera un transiente che dura un solo ciclo e non riesce
        // a spostare l'origine in modo apprezzabile, mentre una deriva geologica genuina,
        // che ripropone lo stesso eccesso ciclo dopo ciclo per minuti, la fa scorrere
        // gradualmente — il budget non si esaurisce mai in modo permanente.
        val newRadial: Float
        if (abs(proposedDrift) > maxDriftFromOrigin) {
            val excess = abs(proposedDrift) - maxDriftFromOrigin
            originRadial += excess * originLeakRate * sign(proposedDrift)
            // Ricalcola il drift rispetto alla nuova origine (già scivolata) e applica
            // comunque un tetto di sicurezza istantaneo: anche con l'origine a
            // scorrimento, un singolo ciclo non deve mai spostare il centro oltre
            // maxDriftFromOrigin dall'origine corrente in un colpo solo.
            val driftFromNewOrigin = proposedRadial - originRadial
            newRadial = if (abs(driftFromNewOrigin) > maxDriftFromOrigin) {
                originRadial + maxDriftFromOrigin * sign(driftFromNewOrigin)
            } else {
                proposedRadial
            }
            driftLimitReached = false
        } else {
            driftLimitReached = false
            newRadial = proposedRadial
        }

        // FIX: tetto assoluto rispetto al Pumping, indipendente da originRadial (che
        // con originLeakRate può scivolare senza limite per inseguire una deriva
        // geologica genuina). Anche una deriva "legittima" ciclo dopo ciclo non deve
        // MAI allontanare il centro oltre maxAbsoluteDriftFromPumping dal valore
        // misurato al Pumping: oltre quel punto è più probabile un accumulo di
        // contaminazione (target deboli assorbiti in GROUND_LOCK) che vero terreno.
        val cappedRadial = if (abs(newRadial - pumpingAnchorRadial) > maxAbsoluteDriftFromPumping) {
            pumpingAnchorRadial + maxAbsoluteDriftFromPumping * sign(newRadial - pumpingAnchorRadial)
        } else {
            newRadial
        }

        val tangentialComponent = -centerI * axisSin + centerQ * axisCos
        centerI = cappedRadial * axisCos - tangentialComponent * axisSin
        centerQ = cappedRadial * axisSin + tangentialComponent * axisCos
        centerRadial = cappedRadial
        updateCount++

        if (abs(limitedStep) > 1e-8f) {
            val normReference = max(stableMovementThreshold, 0.01f)
            val normalizedError = (abs(avgDelta) / normReference).coerceIn(0f, 1f)
            val instantQuality = 1f - normalizedError
            trackingQuality = trackingQuality + 0.1f * (instantQuality - trackingQuality)
            trackingQuality = trackingQuality.coerceIn(0f, 1f)
        }
    }

    /**
     * Accumula il campione corrente per il riassorbimento statico verificato. Confronta
     * ogni nuovo campione con la MEDIA IN COSTRUZIONE (staticAccumI/Q), non con
     * centerRadial: è la differenza chiave rispetto a VERIFY_GROUND, che invece confronta
     * col vecchio centro e per questo scarterebbe sempre un salto di baseline di questa
     * entità (maxAllowedGroundMagnitude e dynamicStabilityLimit sono tarati per la
     * piccola deriva incrementale, non per un centro grossolanamente sbagliato).
     *
     * Un campione incoerente con la media in costruzione (rumore, assestamento in corso)
     * fa ripartire l'accumulo da quel campione, invece di mediare dati inconsistenti —
     * stesso principio di resetVerification() in VERIFY_GROUND. Solo dopo
     * minVerifySampleCount campioni consecutivi coerenti si finalizza la correzione.
     */
    private fun accumulateStaticRecoverySample(sample: IQVector, threshold: Float, nowMs: Long) {
        if (staticSampleCount > 0) {
            val avgI = staticAccumI / staticSampleCount
            val avgQ = staticAccumQ / staticSampleCount
            val avgRadial = avgI * axisCos + avgQ * axisSin
            val avgPhase = atan2(avgQ, avgI).toDegrees()
            val avgMagnitude = IQVector(avgI, avgQ).magnitude

            val sampleRadial = sample.i * axisCos + sample.q * axisSin
            val samplePhase = atan2(sample.q, sample.i).toDegrees()
            val sampleMagnitude = sample.magnitude

            val dynamicStabilityLimit = min(stableMovementThreshold, threshold * groundStabilityThresholdFraction)
            val isStable = abs(sampleRadial - avgRadial) < dynamicStabilityLimit
            val isPhaseStable = angleDiffAbsDeg(samplePhase, avgPhase) < stablePhaseThresholdDeg
            val isMagnitudeStable = abs(sampleMagnitude - avgMagnitude) / max(avgMagnitude, 0.001f) < stableMagnitudeThresholdPercent

            if (!isStable || !isPhaseStable || !isMagnitudeStable) {
                staticAccumI = sample.i
                staticAccumQ = sample.q
                staticSampleCount = 1
                return
            }
        }

        staticAccumI += sample.i
        staticAccumQ += sample.q
        staticSampleCount++

        if (staticSampleCount >= minVerifySampleCount) {
            finalizeStaticRecovery(nowMs)
        }
    }

    /**
     * Applica la media verificata come nuovo centro. Stesso tetto assoluto di sicurezza
     * già usato in applyCorrection() (maxAbsoluteDriftFromPumping): anche un riassorbimento
     * statico confermato da IMU + verifica a N campioni non deve MAI spostare il centro
     * oltre quel limite dal riferimento misurato al Pumping.
     */
    private fun finalizeStaticRecovery(nowMs: Long) {
        val avgI = staticAccumI / staticSampleCount
        val avgQ = staticAccumQ / staticSampleCount
        val avgRadial = avgI * axisCos + avgQ * axisSin

        val cappedRadial = if (abs(avgRadial - pumpingAnchorRadial) > maxAbsoluteDriftFromPumping) {
            pumpingAnchorRadial + maxAbsoluteDriftFromPumping * sign(avgRadial - pumpingAnchorRadial)
        } else {
            avgRadial
        }

        val tangentialComponent = -avgI * axisSin + avgQ * axisCos
        centerI = cappedRadial * axisCos - tangentialComponent * axisSin
        centerQ = cappedRadial * axisSin + tangentialComponent * axisCos
        centerRadial = cappedRadial
        originRadial = cappedRadial
        driftLimitReached = false
        previousRadialDeltaInLock = 0f
        trackingQuality = 1f
        lastUpdateTimeMs = nowMs

        lastGoodCenterRadial = centerRadial
        lastGoodCenterI = centerI
        lastGoodCenterQ = centerQ
        hasLastGoodCenter = true

        resetStaticRecoveryAccumulator()
        // Riparte da qui: se il segnale resta comunque sopra soglia (raro, es. rumore
        // residuo o vero target abbandonato sotto la bobina), non si ricontrolla subito
        // ma solo dopo un altro ciclo pieno di staticReabsorptionTimeoutMs + verifica.
        detectionHoldStartMs = nowMs

        // FIX (bug di propagazione): update() esegue SEMPRE transitionTo(DETECTION) subito
        // dopo questa chiamata (siamo dentro il ramo isDetected==true), quindi getState()
        // resta "DETECTION", mai "GROUND_LOCK". Il ViewModel pubblica il centro corretto a
        // VectorProcessor SOLO quando getState()=="GROUND_LOCK" (vedi DetectorViewModelV2,
        // blocco "GROUND TRACKER"): quella condizione non si verifica MAI lungo questo
        // percorso, quindi centerI/centerQ risultavano aggiornati qui dentro ma mai
        // trasmessi a VectorProcessor — isDetected restava bloccato a prescindere dal
        // riassorbimento avvenuto. Un flag "one-shot", consumato subito dal ViewModel ad
        // ogni chiamata di update() indipendentemente dallo stato, chiude il buco.
        staticRecoveryPending = true

        android.util.Log.d(
            "GroundTracker",
            "🧲 Riassorbimento statico confermato (IMU: TRUE STATIC, $minVerifySampleCount campioni verificati): baseline riallineata"
        )
    }

    private var staticRecoveryPending = false

    /** Da chiamare dal ViewModel subito dopo ogni update(): se true, il centro corrente va
     *  ripubblicato immediatamente a VectorProcessor (vedi commento sopra), indipendentemente
     *  da stato/qualità/isDetected. Consuma il flag (letto una sola volta). */
    fun consumeStaticRecoveryPending(): Boolean {
        val pending = staticRecoveryPending
        staticRecoveryPending = false
        return pending
    }

    private fun resetStaticRecoveryAccumulator() {
        staticAccumI = 0f
        staticAccumQ = 0f
        staticSampleCount = 0
    }

    // =============================================================
    // STATO MOVIMENTO
    // =============================================================

    fun setMotionState(isMoving: Boolean) {
        val nowMs = System.currentTimeMillis()
        if (isMoving) {
            if (!this.isCoilMoving) {
                val gapSinceStop = nowMs - lastStopTimeMs
                if (lastMotionUpdateTimeMs == 0L || gapSinceStop > motionGapToleranceMs) {
                    lastMotionUpdateTimeMs = nowMs
                }
                if (lastStopTimeMs != 0L && gapSinceStop in 1..3000) {
                    recentGapDurationsMs.addLast(gapSinceStop)
                    if (recentGapDurationsMs.size > gapSampleWindowSize) {
                        recentGapDurationsMs.removeFirst()
                    }
                }
                maybeRecalcTolerance(nowMs)
            }
        } else {
            if (this.isCoilMoving) {
                lastStopTimeMs = nowMs
            }
        }
        this.isCoilMoving = isMoving
    }

    private fun maybeRecalcTolerance(nowMs: Long) {
        if (nowMs - lastToleranceRecalcMs < toleranceRecalcIntervalMs) return
        if (recentGapDurationsMs.size < 3) return
        lastToleranceRecalcMs = nowMs
        val sorted = recentGapDurationsMs.sorted()
        val p90Index = (sorted.size * 0.9f).toInt().coerceIn(0, sorted.size - 1)
        val observedP90 = sorted[p90Index]
        motionGapToleranceMs = (observedP90 + toleranceMarginMs).coerceIn(200L, 2500L)
    }

    private fun isCoilMovingValid(nowMs: Long): Boolean {
        if (bypassMotionCheck) return true
        if (!isCoilMoving && (nowMs - lastStopTimeMs) > motionGapToleranceMs) return false
        if (lastMotionUpdateTimeMs == 0L) return false
        return (nowMs - lastMotionUpdateTimeMs) >= minMotionDurationForTrackingMs
    }

    fun setBypassMotionCheck(bypass: Boolean) {
        bypassMotionCheck = bypass
    }

    // =============================================================
    // METODI DI UTILITÀ
    // =============================================================

    private fun resetVerification() {
        verifyAccumulatedDelta = 0f
        verifySampleCount = 0
        verifyPhaseSum = 0f
        verifyMagnitudeSum = 0f
    }


    private fun getCurrentPhase(): Float = atan2(centerQ, centerI).toDegrees()
    private fun getCurrentMagnitude(): Float = getCenter().magnitude
    private fun Float.toDegrees(): Float = this * 180f / PI.toFloat()

    // =============================================================
    // CALIBRAZIONE E GETTER
    // =============================================================

    fun setGroundAxis(axisDeg: Float, center: IQVector) {
        this.axisDeg = axisDeg
        val rad = Math.toRadians(axisDeg.toDouble())
        this.axisCos = cos(rad).toFloat()
        this.axisSin = sin(rad).toFloat()
        this.hasAxis = true

        this.centerI = center.i
        this.centerQ = center.q
        this.centerRadial = center.i * axisCos + center.q * axisSin
        this.originRadial = this.centerRadial

        // Ancora assoluta al Pumping e primo "centro buono": vedi blocco di
        // commenti sopra maxAbsoluteDriftFromPumping.
        this.pumpingAnchorRadial = this.centerRadial
        this.lastGoodCenterRadial = this.centerRadial
        this.lastGoodCenterI = this.centerI
        this.lastGoodCenterQ = this.centerQ
        this.hasLastGoodCenter = true
        this.needsRecalibration = false
        this.recentRecoveryTimestampsMs.clear()

        this.trackingQuality = 1f
        this.updateCount = 0L
        this.driftLimitReached = false
        this.lastUpdateTimeMs = 0L
        this.lastDetectionTimeMs = 0L
        this.detectionHoldStartMs = 0L
        this.staticRecoveryPending = false
        resetStaticRecoveryAccumulator()
        previousRadialDeltaInLock = 0f

        state = State.SEARCH
        stateEnterTimeMs = 0L
        resetVerification()
        groundAccumulatedDelta = 0f
        groundSampleCount = 0
        isCoilMoving = false
        lastMotionUpdateTimeMs = 0L
        lastStopTimeMs = 0L
        bypassMotionCheck = false
        recentGapDurationsMs.clear()
        lastToleranceRecalcMs = 0L
    }

    fun getGroundAngleDeg(): Float = axisDeg
    fun getGroundAxisCos(): Float = axisCos
    fun getGroundAxisSin(): Float = axisSin
    fun getCenter(): IQVector = IQVector(centerI, centerQ)
    fun getTrackingQuality(): Float = trackingQuality
    fun hasGroundAxis(): Boolean = hasAxis
    fun isTracking(): Boolean = hasAxis
    fun getUpdateCount(): Long = updateCount
    fun getLastRadialDelta(): Float = lastRadialDelta
    fun isFrozen(): Boolean = state == State.FREEZE || state == State.DETECTION
    fun isDriftLimitReached(): Boolean = driftLimitReached
    fun getDriftFromOrigin(): Float = centerRadial - originRadial

    /**
     * @deprecated Sposta solo il budget di deriva (originRadial), non il centro
     * tracciato (centerRadial). Se centerRadial è già corrotto (causa più probabile
     * quando questo viene chiamato da un failsafe di "detection bloccata"), il
     * problema si ripresenta al campione successivo. Usare requestBaselineRecovery(),
     * che gestisce anche il ripristino dell'ultimo centro buono e l'eventuale
     * disattivazione del tracking in caso di recuperi ripetuti ravvicinati.
     */
    @Deprecated(
        "Non corregge un centro corrotto, solo il budget di deriva. Usa requestBaselineRecovery().",
        ReplaceWith("requestBaselineRecovery()")
    )
    fun reanchorDriftOrigin() {
        originRadial = centerRadial
        driftLimitReached = false
    }

    /**
     * Punto di ingresso unico per il recupero della baseline quando un sistema
     * esterno (VectorProcessor) rileva che la detection resta bloccata o che il
     * canale indipendente G.ID/G.SET diverge in modo sostenuto (vedi
     * checkGroundIdDivergence in VectorProcessor). Vedi il blocco di commenti sopra
     * maxAbsoluteDriftFromPumping per la logica completa.
     *
     * Ritorna true se il tracking resta attivo dopo il recupero, false se è stato
     * disattivato e serve una nuova calibrazione (vedi needsRecalibration()).
     */
    fun requestBaselineRecovery(nowMs: Long = System.currentTimeMillis()): Boolean {
        recentRecoveryTimestampsMs.addLast(nowMs)
        while (recentRecoveryTimestampsMs.isNotEmpty() &&
            nowMs - recentRecoveryTimestampsMs.first() > recoveryWindowMs
        ) {
            recentRecoveryTimestampsMs.removeFirst()
        }
        val recentCount = recentRecoveryTimestampsMs.size

        if (recentCount >= recoveryDisableCount) {
            // Non è più un episodio isolato: la baseline non è affidabile.
            // Meglio fermarsi qui e chiedere un nuovo Pumping che continuare a
            // "recuperare" un centro che evidentemente non tiene.
            android.util.Log.d("GroundTracker", "🛑 Troppi recuperi ravvicinati ($recentCount in ${recoveryWindowMs}ms): tracking disattivato, serve nuova calibrazione")
            needsRecalibration = true
            hasAxis = false
            return false
        }

        if (recentCount >= recoveryEscalationCount && hasLastGoodCenter) {
            android.util.Log.d("GroundTracker", "↩️ Recupero $recentCount in finestra: ripristino ultimo centro buono")
            centerRadial = lastGoodCenterRadial
            centerI = lastGoodCenterI
            centerQ = lastGoodCenterQ
        }

        originRadial = centerRadial
        driftLimitReached = false
        state = State.SEARCH
        stateEnterTimeMs = nowMs
        resetVerification()
        return true
    }

    fun getState(): String = state.name
    // Query tipizzata per il controllo di flusso (vedi DetectorViewModelV2): getState() ==
    // "GROUND_LOCK" era un confronto su stringa magica, non protetto dal compilatore in
    // caso di rename/aggiunta di uno stato in State.
    fun isGroundLocked(): Boolean = state == State.GROUND_LOCK
    fun isCoilMoving(): Boolean = isCoilMoving

    fun reset() {
        axisDeg = 0f
        axisCos = 1f
        axisSin = 0f
        hasAxis = false
        centerI = 0f
        centerQ = 0f
        centerRadial = 0f
        originRadial = 0f
        trackingQuality = 1f
        updateCount = 0L
        driftLimitReached = false
        lastUpdateTimeMs = 0L
        isCoilMoving = false
        lastMotionUpdateTimeMs = 0L
        lastStopTimeMs = 0L
        lastDetectionTimeMs = 0L
        detectionHoldStartMs = 0L
        staticRecoveryPending = false
        resetStaticRecoveryAccumulator()
        bypassMotionCheck = false
        previousRadialDeltaInLock = 0f

        state = State.SEARCH
        stateEnterTimeMs = 0L
        resetVerification()
        groundAccumulatedDelta = 0f
        groundSampleCount = 0
        recentGapDurationsMs.clear()
        lastToleranceRecalcMs = 0L

        pumpingAnchorRadial = 0f
        hasLastGoodCenter = false
        needsRecalibration = false
        recentRecoveryTimestampsMs.clear()
    }
}