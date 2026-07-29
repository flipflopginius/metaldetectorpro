package com.tetranova.prerex
import kotlin.math.*

data class IQVector(val i: Float, val q: Float) {
    val magnitude: Float get() = sqrt(i * i + q * q)
    operator fun minus(o: IQVector) = IQVector(i - o.i, q - o.q)
    fun distanceTo(o: IQVector): Float = sqrt((i - o.i) * (i - o.i) + (q - o.q) * (q - o.q))
    companion object { val ZERO = IQVector(0f, 0f) }
}

data class VectorResult(
    val vector: IQVector,
    val baseline: IQVector,
    val vectorDistance: Float,
    // Metrica usata per il confronto con la soglia di rilevazione (tangenziale
    // rispetto all'asse di terreno quando calibrato, altrimenti pari a
    // vectorDistance) — vedi VectorProcessor.processSample(). È il valore che
    // decide isDetected, quindi qualunque meter che vuole rappresentare "quanto
    // manca al trigger" deve leggere questo, non vectorDistance: dopo la
    // calibrazione i due possono divergere (es. movimento radiale/pumping che
    // gonfia vectorDistance senza avvicinare affatto la soglia reale).
    val detectionMagnitude: Float,
    // Stima adattiva del rumore osservato dal vivo (scala di detectionMagnitude, PRIMA del
    // moltiplicatore di sicurezza) — vedi VectorProcessor.adaptiveNoiseFloor. Esposta per
    // poterla verificare nei log/CSV diagnostici invece di doverla dedurre a posteriori.
    val adaptiveNoiseFloor: Float,
    val relativeAngleDeg: Float,
    val isDetected: Boolean,
    val confidence: Float,
    val vdi: Int,
    val metalType: String,
    val isAngleValid: Boolean
)

class VectorProcessor {
    var onLogMessage: ((String) -> Unit)? = null
    // FIX: il failsafe a 12s sblocca il flag di detection ma non può toccare la baseline
    // esterna (di competenza del GroundTracker). Senza questo callback, il campione
    // successivo veniva ricalcolato sulla STESSA baseline pinnata da maxDriftFromOrigin,
    // riattivando la detection all'istante — il timeout non aveva alcun effetto reale.
    var onAutoTrackingStuckReset: (() -> Unit)? = null
    var nonFerroLowDeg = 4.0f
    var ferroMinDeg = 5.0f
    // FIX: @Volatile. kVect viene scritto dal thread Main (slider sensibilità, in
    // DetectorViewModelV2) e letto dentro processSample() sul thread Dispatchers.Default
    // della coroutine di acquisizione. Un semplice var non garantisce, sotto la JVM Memory
    // Model, che la scrittura sia visibile immediatamente al thread di elaborazione: senza
    // barriera di memoria il nuovo valore può propagarsi con ritardo non deterministico
    // (da un campione a più campioni). Non introduce un salto nel delta (kVect non tocca
    // filteredInput/compensated, vedi processSample), ma ritarda il momento in cui la
    // nuova soglia di sensibilità viene effettivamente applicata al confronto enter/exit.
    @Volatile
    var kVect = 1.2f
    // Soglia (in multipli di tangentialSigma, il rumore tangenziale misurato ad ogni
    // Pumping) sopra la quale la discriminazione FERRO/NON_FERRO viene considerata
    // affidabile. Sotto questa soglia il segnale utile è comparabile al rumore
    // residuo del terreno e l'angolo calcolato può cambiare segno per pura statistica.
    var idConfidenceK = 2.5f
    var phaseHysteresisDeg = 1.5f
    // RIPRISTINO PRE-REX: 0.9f/0.55f/0.7f erano valori di comodo hardcodati per saltare
    // Air Balance durante una sessione di test rapido (TEST 3), confermato dall'utente.
    // Non sono valori corretti di produzione: al primo Air Balance/Pumping reale vengono
    // comunque sovrascritti da performCalibration()/applyGroundCalibration(), quindi qui
    // servono solo come placeholder pre-calibrazione neutro, ripristinati alla scala
    // originale pre-REX confermata dal confronto diretto con il file pre-transizione.
    var enterDetectionThreshold = 0.15f
    var exitDetectionThreshold = 0.09f
    var noiseMagnitude = 0.01f

    // ===== Soglia adattiva sul rumore osservato dal vivo =====
    // FIX (ridisegno su richiesta esplicita: lo slider di sensibilità non viene mai usato in
    // campo, la soglia giusta è quella prodotta dall'algoritmo, non un numero scelto a mano
    // che copra i casi che l'algoritmo sbaglia). enterDetectionThreshold/exitDetectionThreshold
    // restano il valore di PARTENZA calcolato dalla calibrazione (Air Balance/Pumping, da
    // tangentialRMS) — quel calcolo non viene toccato. Ma tangentialRMS è misurato durante il
    // movimento SU/GIÙ del Pumping, che non è detto rappresenti fedelmente il rumore
    // incontrato dalla bobina durante una spazzolata laterale reale. Invece di coprire
    // un'eventuale sottostima con un numero fisso scelto a tavolino (il vecchio
    // ENTER_THRESHOLD_CLAMP_MIN usato come pavimento comportamentale), il sistema ora osserva
    // detectionMagnitude campione per campione durante l'uso normale — media (adaptiveNoiseMean)
    // E variabilità (adaptiveNoiseSigma) del rumore reale — e alza la soglia SOLO se quanto
    // osservato dal vivo supera quanto previsto dalla calibrazione, mai sotto quel valore
    // (vedi adaptiveNoiseFloor/effectiveEnterThreshold in processSample).
    // Il margine sopra la media non è un moltiplicatore inventato: riusa idConfidenceK, la
    // STESSA costante (2.5 sigma) già usata poco sotto per decidere quando l'angolo di
    // classificazione è affidabile — stesso criterio statistico, un solo numero, non due.
    // Tecnica da "squelch adattivo" nei ricevitori radio: un inseguitore ASIMMETRICO. La
    // MEDIA sale lentamente (alphaUp, non farsi ingannare da un bersaglio vero in
    // avvicinamento, visibile solo per 1-2s durante una spazzolata) e scende rapidamente
    // (alphaDown, riaggancia subito un rumore di fondo tornato basso). La SIGMA fa l'opposto
    // apposta (sale con alphaDown/veloce, scende con alphaUp/lento): un margine di sicurezza
    // deve allargarsi subito se il rumore diventa più instabile, e restringersi solo quando
    // quell'instabilità si conferma durevole, non al primo campione tranquillo.
    // Si azzerano ad ogni nuova calibrazione (vedi setBaselineIQ) perché baseline/asse
    // cambiano e il livello di "quiete" precedente non è più garantito valido.
    var adaptiveNoiseFloor = 0f
        private set
    private var adaptiveNoiseMean = 0f
    private var adaptiveNoiseSigma = 0f
    private val adaptiveFloorAlphaUp = 0.0015f   // costante di tempo ~8-9s a ~13ms/campione
    private val adaptiveFloorAlphaDown = 0.02f   // costante di tempo ~650ms
    var autoRetuneTimeMs = 5000L
    // FIX: failsafe di sicurezza SEPARATO per quando l'auto-tracking ha il controllo della
    // baseline (useExternalBaseline == true). Il failsafe "normale" (autoRetuneTimeMs, 5s)
    // resta disattivato in quel caso per non confliggere col GroundTracker (vedi commento
    // più sotto), ma questo lasciava il sistema SENZA alcuna via d'uscita se la detection
    // restava bloccata a true a lungo (es. baseline esterna lievemente sporcata che spinge
    // detectionMagnitude permanentemente sopra la soglia di uscita: rilevazione continua,
    // lancetta di fase bloccata, nessun reset). Timeout molto più lungo di autoRetuneTimeMs
    // apposta: un vero target durante una spazzolata in movimento non resta rilevato così a
    // lungo, quindi interviene solo in caso di blocco reale, non durante il tracking normale.
    var autoTrackingStuckDetectionTimeoutMs = 12000L
    private var detectionStartTimeMs = 0L

    // Fase ASSOLUTA di riferimento (stesso dominio di rawPhaseDeg).
    // Usata SOLO per la lancetta a schermo e per la rotazione di discriminazione.
    // RIPRISTINO PRE-REX: -162.76f era lo snapshot di una calibrazione reale hardcodato per
    // test (TEST 3), non un default di produzione. Sovrascritto comunque al primo Air
    // Balance/Pumping da applyGroundCalibration()/setBaselineIQ().
    var groundPhaseOffsetDeg = 0f

    // RIPRISTINO PRE-REX: (-150.12, -46.58) era lo snapshot di una baseline reale
    // hardcodato per test (TEST 3), non un default di produzione. Sovrascritto comunque
    // al primo Air Balance/Pumping da setBaselineIQ()/applyGroundCalibration().
    var baselineVector = IQVector.ZERO   // traslazione
    var tangentialRMS = 0f
    var tangentialSigma = 0f
    var groundCenter = IQVector.ZERO
    var discAnglePolarity = 1.0f
    var lastPcaQuality = 1.0f

    // Asse di terreno (dominio Cartesiano del vettore compensato, NON fase assoluta).
    // Usato SOLO per scomporre il segnale in radiale (terreno/pumping, da ignorare)
    // e tangenziale (bersaglio, da rilevare).
    var hasGroundAxis = false
        private set
    var groundAxisDeg = 0f
        private set
    private var groundAxisCos = 1f
    private var groundAxisSin = 0f

    private var smoothedI = 0f
    private var smoothedQ = 0f
    // FIX (bug "spazzolata dopo Pumping fa impazzire la detection"): 0,15 dava una
    // costante di tempo di ~87ms (1/alpha campioni, campionamento ~13ms). L'oscillazione
    // di fase naturale del movimento a mano libera durante una spazzolata reale (misurata
    // da log di campo: 15-20° ogni 500-900ms) è 6-10 volte più lenta della costante di
    // tempo del filtro: un EMA così veloce non la attenua quasi per niente, la lascia
    // passare quasi intatta. Amplificata dalla sottrazione baseline-segnale grezzo (vettori
    // grandi, quasi uguali — vedi corda 2R·sin(θ/2)), produce salti fino a ~90 unità nel
    // segnale compensato, ben oltre la soglia di detection. 0,05 (costante di tempo
    // ~260ms) attenua l'oscillazione di oltre il 60% restando comunque molto più reattivo
    // delle temporizzazioni con cui il resto della pipeline già ragiona su un evento di
    // rilevamento reale (freezeAfterDetectionMs=1200ms, groundLockDurationMs=3000ms).
    private val glenEmaAlpha = 0.05f
    private var phaseFilterSin = 0f
    private var phaseFilterCos = 1f
    private val phaseDiffAlpha = 0.25f
    private var internalIsDetected = false

    // ===== NUOVO: Supporto per Autotracking =====
    private var externalBaselineOverride: IQVector? = null
    private var externalAxisOverride: Float? = null
    private var useExternalBaseline = false
    private var useExternalAxis = false

    // =============================================================
    // G.ID / G.SET — lettura di terreno indipendente da detection/tracking
    // =============================================================
    // Come nei detector commerciali di fascia alta: G.SET è il riferimento
    // calibrato al Pumping (groundPhaseOffsetDeg, statico); G.ID è la lettura
    // CONTINUA e in tempo reale del terreno, calcolata direttamente sulla fase
    // grezza (rawPhaseDeg), che quindi non dipende in alcun modo da isDetected,
    // dalla baseline esterna del GroundTracker o dallo stato del tracker stesso.
    // Questa indipendenza è il punto: è l'unico segnale che resta AFFIDABILE
    // anche quando la baseline esterna è corrotta e isDetected è bloccato a
    // true (vedi checkGroundIdDivergence sotto) — è esattamente ciò che manca
    // oggi come canale di verifica per rompere il deadlock dei falsi positivi.
    var groundIdAlpha = 0.01f   // EMA molto lenta (τ ≈ 100 campioni ≈ 1s a 100Hz)
    private var groundIdEmaDeg = 0f
    private var hasGroundIdEma = false

    fun getGroundIdDeg(): Float = groundIdEmaDeg
    fun getGroundSetDeg(): Float = groundPhaseOffsetDeg

    // Se G.ID diverge da G.SET oltre questa soglia per un tempo sostenuto MENTRE
    // l'auto-tracking ha il controllo della baseline, la baseline esterna è
    // corrotta — indipendentemente da cosa dice isDetected in quel momento (che
    // durante un deadlock è la vittima della corruzione, non un indicatore
    // affidabile). Forza il recupero a prescindere dallo stato di detection,
    // rompendo il loop "bloccato -> sblocco flag -> rilevo di nuovo -> bloccato".
    var groundIdDivergenceThresholdDeg = 6f
    var groundIdDivergenceSustainedMs = 4000L
    private var divergenceSinceMs = 0L

    // =============================================================
    // FIX: TARGET ID LOCK (discriminazione bloccata al picco di ampiezza)
    // =============================================================
    // Problema osservato sul campo: in USCITA (allontanamento dal target dopo averlo
    // correttamente classificato in ENTRATA), per un istante l'angolo di fase può invertirsi
    // di segno rispetto a quello letto in entrata, facendo leggere ad es. "NON_FERRO" un
    // target già classificato correttamente come "FERRO". Non è un errore del filtro:
    //  1) in uscita l'ampiezza scende verso la soglia di uscita, il rapporto segnale/rumore
    //     peggiora e l'angolo (atan2 su un vettore sempre più corto) diventa statisticamente
    //     instabile;
    //  2) per molti target (specie ferrosi, per effetto pelle/correnti parassite) la vera
    //     risposta di fase a bassissimo accoppiamento può realmente ruotare rispetto a quella
    //     osservata al picco: è fisica del target, non rumore da filtrare.
    // Soluzione (stessa tecnica usata nei detector professionali, "Target ID Lock"): la
    // classificazione (metalType/VDI) e l'angolo mostrato a schermo vengono ricalcolati
    // normalmente SOLO mentre l'ampiezza sta salendo verso un nuovo massimo durante l'evento
    // di rilevamento; una volta superato il picco (ampiezza in discesa) il valore resta
    // congelato a quello letto al picco — il punto di massima affidabilità del segnale — fino
    // a quando il sistema non torna sotto soglia di uscita (fine dell'evento), momento in cui
    // il lock si resetta per il target successivo. La barra di ampiezza (vectorDistance) e la
    // confidenza restano invece live: mostrare il target che si affievolisce in uscita è
    // corretto, è solo la sua CLASSIFICAZIONE a non dover più cambiare una volta stabilita.
    // IMPORTANTE: il picco va tracciato sul modulo TOTALE del vettore compensato
    // (magnitude), non su detectionMagnitude (la proiezione tangenziale rispetto all'asse
    // di terreno). Il modulo totale è fisicamente pulito: sale, ha UN picco, scende, in modo
    // monotono in ciascuna fase della spazzolata. La proiezione tangenziale invece può
    // risalire localmente anche mentre il modulo totale sta già scendendo, perché la
    // DIREZIONE del vettore ruota mentre il bersaglio si allontana (specie nei ferrosi, per
    // effetto pelle/correnti parassite a bassissimo accoppiamento — la stessa fisica del
    // Target ID Lock descritta sopra). Tracciare il picco su quella proiezione fa sì che
    // piccoli rimbalzi in uscita riaprano il lock e lo riaggiornino con l'angolo instabile
    // del momento, che vicino alla soglia di uscita può aver scavalcato lo zero: il freeze
    // smette di tenere proprio nella fase in cui serve di più.
    private var detectionPeakMagnitude = 0f
    private var lockedMetalType = "AMBIGUO"
    private var lockedVdi = 0
    private var lockedDisplayAngleDeg = 0f
    private var lockedAngleValid = false

    private fun resetTargetIdLock() {
        detectionPeakMagnitude = 0f
        lockedMetalType = "AMBIGUO"
        lockedVdi = 0
        lockedDisplayAngleDeg = 0f
        lockedAngleValid = false
    }

    // =============================================================
    // FIX: FINESTRA DI ASSESTAMENTO POST-CALIBRAZIONE
    // =============================================================
    // Subito dopo il pumping, smoothedI/Q viene agganciato esattamente alla nuova baseline
    // ma il segnale reale in ingresso non coincide subito con essa (l'assestamento fisico
    // della mano dopo il gesto di pumping richiede qualche istante). Il filtro EMA che
    // riconverge produce un picco transitorio di Energia, letto erroneamente come un
    // bersaglio. Sopprimiamo la detection per una breve finestra nota.
    private var calibrationSettleUntilMs = 0L
    // FIX: 2000ms erano ampiamente sufficienti (~23 costanti di tempo del vecchio
    // glenEmaAlpha=0,15, ~87ms) ma restano generosi anche con l'attuale 0,05 (~260ms di
    // costante di tempo: 800ms = ~3 costanti di tempo, filtro assestato oltre il 95%).
    // 2s di sordità completa dopo OGNI Pumping, specie se ripetuto più volte in una
    // sessione di test ravvicinata, si somma e dà la sensazione di scarsa reattività.
    var calibrationSettleTimeMs = 800L   // finestra di assestamento post-pumping

    // =============================================================
    // API PER AUTOTRACKING
    // =============================================================

    /**
     * Imposta una baseline esterna (dal GroundTracker).
     * Usato per l'autotracking continuo.
     */
    fun setExternalBaseline(vector: IQVector) {
        externalBaselineOverride = vector
        useExternalBaseline = true
    }

    /**
     * Imposta un asse esterno (dal GroundTracker).
     * Usato per l'autotracking continuo.
     */
    fun setExternalAxis(axisDeg: Float) {
        externalAxisOverride = axisDeg
        useExternalAxis = true
    }

    /**
     * Disabilita l'uso di baseline e asse esterni (torna alla modalità normale).
     */
    fun clearExternalOverrides() {
        useExternalBaseline = false
        useExternalAxis = false
        externalBaselineOverride = null
        externalAxisOverride = null
    }

    /**
     * Verifica se la baseline esterna è attiva.
     */
    fun isUsingExternalBaseline(): Boolean = useExternalBaseline

    /**
     * Verifica se l'asse esterno è attivo.
     */
    fun isUsingExternalAxis(): Boolean = useExternalAxis

    // =============================================================
    // ELABORAZIONE CAMPIONE
    // =============================================================

    fun processSample(rawDelta: Float, rawPhaseDeg: Float): VectorResult {
        val rad = Math.toRadians(rawPhaseDeg.toDouble())
        val realI = (rawDelta * cos(rad)).toFloat()
        val realQ = (rawDelta * sin(rad)).toFloat()

        if (smoothedI == 0f && smoothedQ == 0f) {
            smoothedI = realI
            smoothedQ = realQ
        } else {
            smoothedI += glenEmaAlpha * (realI - smoothedI)
            smoothedQ += glenEmaAlpha * (realQ - smoothedQ)
        }

        val filteredInput = IQVector(smoothedI, smoothedQ)

        // ===== Baseline: usa esterna se disponibile =====
        val effectiveBaseline = if (useExternalBaseline && externalBaselineOverride != null) {
            externalBaselineOverride!!
        } else {
            baselineVector
        }

        val compensated = filteredInput - effectiveBaseline
        val magnitude = compensated.magnitude

        // ===== Asse di terreno: usa esterno se disponibile =====
        val externalAxis = externalAxisOverride.takeIf { useExternalAxis && it != null }
        val effectiveAxisCos: Float
        val effectiveAxisSin: Float
        val effectiveHasAxis: Boolean
        if (externalAxis != null) {
            val rad2 = Math.toRadians(externalAxis.toDouble())
            effectiveAxisCos = cos(rad2).toFloat()
            effectiveAxisSin = sin(rad2).toFloat()
            effectiveHasAxis = true
        } else {
            effectiveAxisCos = groundAxisCos
            effectiveAxisSin = groundAxisSin
            effectiveHasAxis = hasGroundAxis
        }

        // Componente tangenziale del vettore compensato rispetto all'asse di terreno (PCA),
        // quando disponibile: è la proiezione perpendicolare all'asse. Usata SOLO in valore
        // assoluto: per la soglia di rilevazione (detectionMagnitude = abs(groundTangential))
        // e per la soglia di affidabilità dell'ID (confronto con tangentialSigma). Non va MAI
        // usata con segno per calcolare un angolo di classificazione: anche con l'asse
        // orientato in modo deterministico (vedi applyGroundCalibration), resta comunque una
        // retta diversa da groundPhaseOffsetDeg, sfalsata di un angolo variabile calibrazione
        // per calibrazione — un angolo con segno calcolato in questo frame introdurrebbe uno
        // sfalsamento sistematico rispetto a ciò che viene mostrato a schermo (che usa
        // sempre il frame di groundPhaseOffsetDeg), non semplice rumore. Vedi il blocco di
        // classificazione più sotto.
        val groundTangential = -compensated.i * effectiveAxisSin + compensated.q * effectiveAxisCos

        // Metrica usata per la SOGLIA di rilevazione
        val detectionMagnitude = if (effectiveHasAxis) {
            abs(groundTangential)
        } else {
            magnitude
        }

        val wasDetected = internalIsDetected
        val now = System.currentTimeMillis()

        // ★ FIX: finestra di assestamento post-calibrazione
        val inSettleWindow = now < calibrationSettleUntilMs

        // Aggiorna la soglia adattiva PRIMA di calcolarla per questo campione, usando
        // l'esito del campione PRECEDENTE (wasDetected) per decidere se ci troviamo in un
        // periodo presumibilmente "silenzioso": evita qualunque circolarità (il campione non
        // può influenzare la soglia con cui viene giudicato lui stesso), e in settle window
        // il transitorio del filtro non è rumore rappresentativo, quindi va escluso.
        if (!wasDetected && !inSettleWindow) {
            val deviation = detectionMagnitude - adaptiveNoiseMean
            adaptiveNoiseMean += if (deviation > 0f) {
                adaptiveFloorAlphaUp * deviation
            } else {
                adaptiveFloorAlphaDown * deviation
            }
            val absDeviation = abs(deviation)
            adaptiveNoiseSigma += if (absDeviation > adaptiveNoiseSigma) {
                adaptiveFloorAlphaDown * (absDeviation - adaptiveNoiseSigma)
            } else {
                adaptiveFloorAlphaUp * (absDeviation - adaptiveNoiseSigma)
            }
            adaptiveNoiseFloor = adaptiveNoiseMean + idConfidenceK * adaptiveNoiseSigma
        }

        // Applica la sensibilità. La soglia effettiva è la PIÙ ALTA tra quella calcolata
        // dall'algoritmo di calibrazione e quella richiesta dal rumore osservato dal vivo
        // (mai il contrario: l'algoritmo di calibrazione resta comunque un limite inferiore).
        val calibratedEnterThreshold = enterDetectionThreshold / kVect
        val calibratedExitThreshold = exitDetectionThreshold / kVect
        val effectiveEnterThreshold = max(calibratedEnterThreshold, adaptiveNoiseFloor)
        val exitToEnterRatio = if (calibratedEnterThreshold > 0.0001f) {
            calibratedExitThreshold / calibratedEnterThreshold
        } else {
            0.6f
        }
        val effectiveExitThreshold = effectiveEnterThreshold * exitToEnterRatio

        internalIsDetected = if (inSettleWindow) {
            // Durante la finestra di assestamento, sopprimiamo la detection per evitare
            // che il transitorio del filtro EMA venga scambiato per un bersaglio.
            false
        } else if (wasDetected) {
            detectionMagnitude > effectiveExitThreshold
        } else {
            detectionMagnitude > effectiveEnterThreshold
        }

        // Controllo Timer e Auto-Retune di sicurezza
        if (internalIsDetected && !wasDetected) {
            detectionStartTimeMs = now
            // FIX: nuovo evento di rilevamento (fronte di salita) -> resetta il Target ID
            // Lock. Da qui riparte la ricerca del picco per il nuovo target.
            resetTargetIdLock()
        } else if (internalIsDetected && !useExternalBaseline && (now - detectionStartTimeMs > autoRetuneTimeMs)) {
            // FIX (bug "rilevazione bloccata per sempre, anche senza auto-tracking"):
            // questo ramo prima chiamava setBaselineIQ(filteredInput), cioè usava il segnale
            // ATTUALE come nuovo zero. Se la detection resta bloccata per più di
            // autoRetuneTimeMs perché la bobina è ferma sopra (o vicino) un metallo vero —
            // non per una deriva di baseline genuina — questo insegna al sistema che il
            // metallo È il terreno: al campione successivo il vero terreno torna a leggersi
            // come un delta enorme rispetto alla nuova baseline sbagliata, la detection
            // riscatta, e allo scadere del timeout successivo ci si ri-azzera di nuovo sullo
            // STESSO segnale contaminato — un loop infinito indistinguibile dall'esterno da
            // "il sistema è bloccato in rilevazione". Non c'è modo di distinguere qui, solo
            // dal tempo trascorso, "baseline davvero da rifare" da "c'è ancora un target
            // vero sotto la bobina": bisogna trattarli allo stesso modo prudente già usato
            // sotto per il percorso con auto-tracking, che infatti non soffre di questo bug —
            // sblocca solo il flag (il prossimo campione rivaluta da zero con
            // effectiveEnterThreshold, non resta ostaggio della soglia di uscita più bassa),
            // senza toccare la baseline. Se il segnale è realmente sceso, la nuova valutazione
            // lo confermerà; se è ancora un target vero, la detection si riaggancia
            // correttamente invece di corrompere lo zero.
            internalIsDetected = false
            resetTargetIdLock()
            phaseFilterSin = 0f
            phaseFilterCos = 1f
            onLogMessage?.invoke("⚠️ Rilevamento bloccato >${autoRetuneTimeMs / 1000}s: reset forzato del flag di detection (baseline non toccata).")
        } else if (internalIsDetected && useExternalBaseline && (now - detectionStartTimeMs > autoTrackingStuckDetectionTimeoutMs)) {
            // FIX: vedi commento su autoTrackingStuckDetectionTimeoutMs sopra. NON tocchiamo
            // la baseline qui (è il GroundTracker ad averne il controllo): ci limitiamo a
            // sbloccare il flag di detection e il Target ID Lock, così il prossimo campione
            // rivaluta la detection da zero (detectionMagnitude > effectiveEnterThreshold)
            // invece di restare ostaggio della soglia di uscita più bassa. Questo riapre
            // anche la finestra "!vr.isDetected" nel ViewModel che permette al GroundTracker
            // di rinfrescare baseline/asse esterni, rompendo il loop.
            internalIsDetected = false
            resetTargetIdLock()
            // FIX: senza questo, il GroundTracker restava pinnato sul vecchio budget di
            // deriva (maxDriftFromOrigin) e riproponeva la stessa baseline errata al
            // campione successivo, vanificando il reset del flag qui sopra.
            onAutoTrackingStuckReset?.invoke()
            onLogMessage?.invoke("⚠️ Rilevamento bloccato >${autoTrackingStuckDetectionTimeoutMs / 1000}s in auto-tracking: reset forzato del flag di detection e ri-ancoraggio del drift.")
        }

        // ===== G.ID: aggiornato SEMPRE, su rawPhaseDeg puro =====
        // Deliberatamente PRIMA/fuori da qualunque logica di detection o baseline:
        // deve restare un canale pulito, mai contaminato da ciò che sta accadendo
        // nel resto della pipeline.
        if (!hasGroundIdEma) {
            groundIdEmaDeg = rawPhaseDeg
            hasGroundIdEma = true
        } else {
            val diff = wrapAngleDeg180(rawPhaseDeg - groundIdEmaDeg)
            groundIdEmaDeg = wrapAngleDeg180(groundIdEmaDeg + groundIdAlpha * diff)
        }
        checkGroundIdDivergence(now)

        val relativePhase = wrapAngleDeg180(rawPhaseDeg - groundPhaseOffsetDeg)
        val relRad = Math.toRadians(relativePhase.toDouble())
        phaseFilterSin += phaseDiffAlpha * (sin(relRad).toFloat() - phaseFilterSin)
        phaseFilterCos += phaseDiffAlpha * (cos(relRad).toFloat() - phaseFilterCos)

        val smoothedPhase = Math.toDegrees(atan2(phaseFilterSin.toDouble(), phaseFilterCos.toDouble())).toFloat()
        val liveDisplayAngleDeg = smoothedPhase * discAnglePolarity

        var metalType = "AMBIGUO"
        var vdi = 0

        // Affidabilità della discriminazione. Dopo il Pumping usiamo lo stesso rumore
        // tangenziale misurato in QUELLA specifica calibrazione (tangentialSigma) e la
        // stessa detectionMagnitude già usata per il rilevamento, invece di confrontare
        // il modulo grezzo con una soglia di rumore generica: calibrazione per
        // calibrazione, decidiamo dinamicamente quando il segnale è abbastanza forte da
        // fidarsi dell'angolo. In sola aria (nessun asse di terreno/nessun tangentialSigma
        // disponibile) resta il criterio originale.
        val isAngleValidCheck = if (effectiveHasAxis && tangentialSigma > 0f) {
            detectionMagnitude > (idConfidenceK * tangentialSigma)
        } else {
            magnitude > (noiseMagnitude * 1.5f)
        }

        if (magnitude > 0.001f && internalIsDetected && isAngleValidCheck) {
            // Angolo di classificazione: SEMPRE nel riferimento di fase assoluta
            // (groundPhaseOffsetDeg), sia in aria che dopo il Pumping — lo stesso identico
            // riferimento già usato per l'angolo mostrato a schermo (vedi smoothedPhase più
            // sopra), quindi per costruzione coerente con ciò che l'operatore osserva.
            // NOTA: NON usare il frame dell'asse di terreno (radiale/tangenziale) qui, anche
            // se l'asse è orientato in modo deterministico (vedi applyGroundCalibration).
            // L'asse PCA e groundPhaseOffsetDeg restano due rette diverse, sfalsate di un
            // angolo variabile (fino a 90°) da un Pumping all'altro: anche disambiguato il
            // verso, il classificatore nel frame dell'asse può posizionare lo zero in un
            // punto diverso da quello mostrato a schermo, causando un'inversione SISTEMATICA
            // (non rumore statistico) — è quello che si è osservato: angolo negativo a
            // schermo ma classificazione NON_FERRO. groundPhaseOffsetDeg è l'unico
            // riferimento che garantisce coerenza tra ciò che si vede e ciò che si classifica.
            val rawAngle = atan2(compensated.q, compensated.i) * (180f / PI.toFloat())
            val angleDeg = wrapAngleDeg180(rawAngle - groundPhaseOffsetDeg) * discAnglePolarity

            if (angleDeg > nonFerroLowDeg) {
                metalType = "NON_FERRO"
                vdi = angleDeg.roundToInt().coerceIn(1, 99)
            } else if (angleDeg < -ferroMinDeg) {
                metalType = "FERRO"
                vdi = angleDeg.roundToInt().coerceIn(-99, -1)
            }
        }

        // FIX: aggiorna il Target ID Lock SOLO mentre l'ampiezza sta salendo verso un nuovo
        // massimo. Superato il picco (ampiezza in discesa, tipico della fase di uscita), il
        // lock smette di aggiornarsi e la classificazione/angolo restano congelati al valore
        // più affidabile registrato al picco, invece di rincorrere l'angolo instabile della
        // discesa. Il confronto >= (non solo >) fa sì che un piccolo plateau al picco continui
        // ad aggiornare il lock con l'ultima lettura, non solo la primissima.
        // Il picco è tracciato sul modulo TOTALE (magnitude), non su detectionMagnitude: solo
        // il modulo totale sale-e-scende in modo pulito e monotono in ciascuna fase della
        // spazzolata. La proiezione tangenziale rispetto all'asse (detectionMagnitude) può
        // invece risalire localmente anche a modulo totale già in discesa, perché la
        // direzione del vettore ruota mentre il bersaglio si allontana — usarla qui riapriva
        // il lock durante l'uscita e lo faceva riaggiornare con l'angolo instabile del
        // momento (vedi nota sul campo detectionPeakMagnitude sopra).
        if (internalIsDetected && magnitude >= detectionPeakMagnitude) {
            detectionPeakMagnitude = magnitude
            lockedMetalType = metalType
            lockedVdi = vdi
            lockedDisplayAngleDeg = liveDisplayAngleDeg
            lockedAngleValid = isAngleValidCheck
        }

        // Durante un evento di rilevamento si espone il valore bloccato al picco; fuori da un
        // evento (ricerca/idle) si espone il valore live, esattamente come nella versione
        // originale (nessun cambiamento di comportamento fuori da un rilevamento).
        val outputMetalType = if (internalIsDetected) lockedMetalType else "AMBIGUO"
        val outputVdi = if (internalIsDetected) lockedVdi else 0
        val outputDisplayAngleDeg = if (internalIsDetected) lockedDisplayAngleDeg else liveDisplayAngleDeg
        val outputAngleValid = if (internalIsDetected) lockedAngleValid else isAngleValidCheck

        // Calcolo della confidenza (0-100%): resta LIVE (basata sull'ampiezza corrente, non
        // sull'angolo) — è corretto che scenda mentre il target si allontana in uscita.
        val confidenceValue = if (internalIsDetected) {
            val ratio = (magnitude / effectiveEnterThreshold).coerceIn(1f, 5f)
            (((ratio - 1f) / 4f) * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }

        return VectorResult(
            vector = filteredInput,
            baseline = effectiveBaseline,
            vectorDistance = magnitude,
            detectionMagnitude = detectionMagnitude,
            adaptiveNoiseFloor = adaptiveNoiseFloor,
            relativeAngleDeg = outputDisplayAngleDeg,
            isDetected = internalIsDetected,
            confidence = confidenceValue,
            vdi = outputVdi,
            metalType = outputMetalType,
            isAngleValid = outputAngleValid
        )
    }

    /**
     * Canale di verifica indipendente da isDetected (vedi commento su
     * groundIdEmaDeg). Attivo SOLO quando l'auto-tracking ha il controllo della
     * baseline: fuori da quel caso G.SET è comunque il riferimento corretto e non
     * c'è nulla da correggere. Il ri-armo dopo un recupero avviene solo quando la
     * divergenza rientra sotto soglia, per evitare di richiamare il recupero ad
     * ogni singolo campione mentre la baseline resta corrotta (requestBaselineRecovery
     * nel GroundTracker gestisce comunque l'escalation su recuperi ravvicinati).
     */
    private fun checkGroundIdDivergence(nowMs: Long) {
        if (!useExternalBaseline) {
            divergenceSinceMs = 0L
            return
        }
        val diff = angleDiffAbsDeg(groundIdEmaDeg, groundPhaseOffsetDeg)
        if (diff > groundIdDivergenceThresholdDeg) {
            if (divergenceSinceMs == 0L) divergenceSinceMs = nowMs
            if (nowMs - divergenceSinceMs > groundIdDivergenceSustainedMs) {
                divergenceSinceMs = nowMs
                internalIsDetected = false
                resetTargetIdLock()
                onAutoTrackingStuckReset?.invoke()
                onLogMessage?.invoke(
                    "⚠️ G.ID diverge da G.SET di %.1f° (>%.0f°) da oltre %ds: recupero forzato baseline, indipendente da detection."
                        .format(diff, groundIdDivergenceThresholdDeg, groundIdDivergenceSustainedMs / 1000)
                )
            }
        } else {
            divergenceSinceMs = 0L
        }
    }

    fun setBaselineIQ(vector: IQVector) {
        this.baselineVector = vector
        this.smoothedI = vector.i
        this.smoothedQ = vector.q
        // Se stiamo usando una baseline esterna, la disabilitiamo
        if (useExternalBaseline) {
            useExternalBaseline = false
            externalBaselineOverride = null
        }
        // Nuova calibrazione (Air Balance o Pumping, quest'ultimo passa da qui via
        // applyGroundCalibration): baseline/asse cambiano, il livello di "quiete" imparato
        // dalla calibrazione precedente non è più garantito rappresentativo. Si riparte da
        // zero: fino a che l'inseguitore non ha nuovamente imparato nulla, effectiveEnterThreshold
        // coincide esattamente con la soglia calcolata dall'algoritmo, come prima di questa
        // modifica.
        adaptiveNoiseMean = 0f
        adaptiveNoiseSigma = 0f
        adaptiveNoiseFloor = 0f
    }

    fun setNoiseEstimate(noise: Float) {
        this.noiseMagnitude = noise.coerceAtLeast(0.001f)
    }

    fun setDiscThresholds(low: Float, high: Float) {
        this.nonFerroLowDeg = low
        this.ferroMinDeg = high
    }

    /** Applica il risultato della calibrazione a pompaggio: baseline, fase assoluta e asse di terreno, in un'unica operazione atomica. */
    fun applyGroundCalibration(baseline: IQVector, phaseOffsetDeg: Float, groundAxisDeg: Float) {
        setBaselineIQ(baseline)
        groundPhaseOffsetDeg = phaseOffsetDeg

        // La PCA del Pumping individua una RETTA di massima varianza (θ equivalente a
        // θ+180°), non un verso: l'autovettore può uscire con segno arbitrario a seconda
        // della calibrazione, anche a parità di terreno reale. Qui, all'atto della
        // calibrazione, fissiamo IN MODO DETERMINISTICO il verso dell'asse scegliendo quello
        // dei due più vicino al riferimento di fase assoluta appena misurato
        // (groundPhaseOffsetDeg, che non è ambiguo): se sono a più di 90° di distanza,
        // ruotiamo l'asse di 180°. Da qui in poi l'asse ha un verso stabile e riproducibile,
        // calibrazione dopo calibrazione, invece di poter "saltare" arbitrariamente da un
        // lato all'altro — è questa instabilità di verso, non l'idea in sé, ad aver causato
        // la classificazione sistematicamente ribaltata osservata usando l'asse grezzo per
        // un angolo con segno.
        val diff = wrapAngleDeg180(groundAxisDeg - phaseOffsetDeg)
        val orientedAxisDeg = if (abs(diff) > 90f) wrapAngleDeg180(groundAxisDeg + 180f) else groundAxisDeg

        setGroundAxis(orientedAxisDeg)
        phaseFilterSin = 0f
        phaseFilterCos = 1f
        internalIsDetected = false
        // Riallinea G.ID a G.SET: lo scostamento riparte da zero ad ogni nuova
        // calibrazione, invece di trascinarsi dietro la deriva della sessione precedente.
        groundIdEmaDeg = phaseOffsetDeg
        hasGroundIdEma = true
        divergenceSinceMs = 0L
        // Disabilita overrides esterni quando si fa una calibrazione manuale
        clearExternalOverrides()

        // ★ FIX: finestra di assestamento post-pumping
        // smoothedI/Q è stato agganciato esattamente alla nuova baseline, ma il segnale
        // reale in ingresso non coincide subito con essa (assestamento fisico della mano
        // dopo il gesto di pumping). Il filtro EMA che riconverge produce un picco
        // transitorio di Energia. Sopprimiamo la detection per una finestra nota.
        calibrationSettleUntilMs = System.currentTimeMillis() + calibrationSettleTimeMs
    }

    /** Imposta solo l'asse di terreno (per il bilanciamento manuale), senza toccare baseline o fase assoluta. */
    fun setGroundAxis(axisDeg: Float) {
        val rad = Math.toRadians(axisDeg.toDouble())
        groundAxisCos = cos(rad).toFloat()
        groundAxisSin = sin(rad).toFloat()
        groundAxisDeg = axisDeg
        hasGroundAxis = true
        // Se stiamo usando un asse esterno, lo disabilitiamo
        if (useExternalAxis) {
            useExternalAxis = false
            externalAxisOverride = null
        }
    }

    fun clearGroundAxis() {
        hasGroundAxis = false
        groundAxisDeg = 0f
        groundAxisCos = 1f
        groundAxisSin = 0f
        clearExternalOverrides()
    }

    fun reset() {
        smoothedI = 0f
        smoothedQ = 0f
        phaseFilterSin = 0f
        phaseFilterCos = 1f
        internalIsDetected = false
        detectionStartTimeMs = 0L
        resetTargetIdLock()
        calibrationSettleUntilMs = 0L
        hasGroundIdEma = false
        groundIdEmaDeg = 0f
        divergenceSinceMs = 0L
        clearExternalOverrides()
    }
}