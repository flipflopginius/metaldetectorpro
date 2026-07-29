package com.tetranova.prerex

import kotlin.math.*

/**
 * Rappresenta l'esito finale dell'elaborazione di calibrazione del terreno.
 */
data class GroundCalibrationReport(
    val success: Boolean,
    val groundAngleDeg: Float,
    val centerPhaseDeg: Float,
    val groundCenter: IQVector,
    val pcaQuality: Float,
    val tangentialRMS: Float,
    val tangentialSigma: Float,
    val message: String
)

/**
 * Diagnostica live durante il pumping, restituita da addSample().
 * La UI può usarla per mostrare progresso in tempo reale.
 */
data class PumpingLiveDiagnostics(
    val sampleCount: Int,
    val samplesRequired: Int,
    val provisionalPcaQuality: Float,   // -1f finché non calcolabile (< 100 campioni)
    val oscillationCount: Int,
    val currentAmplitude: Float,
    val center: IQVector,               // centroide corrente, per il widget IQPlotWidget
    // FIX (5° giro): span (max-min) dell'ampiezza filtrata osservato PRIMA che venga
    // riconosciuto un vero movimento (currentTrend == 0), cioè il rumore di fondo a
    // bobina ferma su QUESTO hardware. Serve a tarare movementThreshold su un dato
    // misurato invece che su una cifra ipotizzata a tavolino (vedi commento su
    // movementThreshold più sotto). Esegui un test a bobina ferma sul tavolo, leggi
    // il valore massimo raggiunto da questo campo nel log/UI, e imposta
    // movementThreshold a 2-3 volte quel valore prima del prossimo test di pumping reale.
    val restNoiseAmplitudeSpan: Float
)

class GroundCalibrator {
    private val samples = ArrayDeque<IQVector>()

    // Somme correnti di samples, mantenute incrementalmente: addSample() gira fino a
    // 500 volte/secondo sul thread principale (vedi DetectorViewModelV2), e con una
    // finestra di 1000 campioni un ricalcolo completo ad ogni chiamata era un doppio
    // giro O(n) evitabile solo per il centroide (il picco di distanza massima resta
    // O(n): rimuovere il campione più vecchio potrebbe scartare proprio il massimo,
    // quindi non è incrementale in modo altrettanto semplice).
    private var runningSumI = 0.0
    private var runningSumQ = 0.0

    // FIX (4° giro): a 500 campioni/secondo (confermato dall'utente sul proprio
    // hardware), 150 campioni sono solo 0,3s — un pumping umano reale (0,8-1,5s a
    // mezza corsa) non ci sta dentro per intero, la PCA veniva calcolata su un
    // frammento del movimento, non sul movimento completo. Portato a 1000 campioni
    // (2s), sufficienti per 2-3 pompate intere anche a ritmo lento.
    var maxSamplesWindow = 1000
    var minSamplesRequired = 500
    var minOscillationsRequired = 5

    // ===== NUOVO: diagnostica in tempo reale =====
    private var oscillationCount = 0

    // FIX (4° giro — filtro passa-basso PRIMA del rilevamento di tendenza): il
    // pumping è un movimento oscillatorio verticale inequivocabile — quando la bobina
    // SCENDE verso il terreno l'accoppiamento aumenta e l'ampiezza grezza del segnale
    // (sample.magnitude, che coincide col delta grezzo di questo progetto) SALE;
    // quando la bobina SALE, l'ampiezza SCENDE. Un'oscillazione completa è una
    // tendenza che si inverte DUE volte — un singolo movimento di posizionamento
    // (solo in una direzione) non la genera mai, a prescindere da quando avviene.
    private val amplitudeFilterWindow = 25
    private val amplitudeHistory = ArrayDeque<Float>()
    private var hasLastAmplitude = false

    // FIX (5° giro — sostituita la logica a streak con isteresi/Schmitt trigger):
    // il giro precedente confermava un'inversione dopo N campioni CONSECUTIVI
    // (candidateStreak >= trendConfirmSamples = 30) in cui la media a 25 campioni
    // saliva o scendeva in modo strettamente monotono. Sui log dell'utente questo
    // non si verifica MAI su un ADC reale: basta un singolo micro-ripple della media
    // (fisiologico anche su segnale già filtrato) per azzerare lo streak a 1, quindi
    // candidateStreak non raggiunge 30 né durante il pumping reale (oscillationCount
    // resta a 0 nonostante 10+ pompate) né mai, ovviamente, a bobina ferma.
    //
    // Sostituito con un trigger di Schmitt: si insegue il picco (o la valle) più
    // recente e si conferma un'inversione SOLO quando il segnale si allontana da
    // quel picco/valle di più di movementThreshold. Non dipende più dal numero di
    // campioni consecutivi monotoni: un micro-ripple in mezzo a un movimento reale
    // non azzera nulla, perché l'estremo continua a essere inseguito correttamente
    // finché non c'è una vera inversione fisica.
    private var currentTrend = 0   // 0 = in attesa, 1 = in salita, -1 = in discesa
    private var extremum = 0f      // ultimo picco (se currentTrend=1) o valle (se currentTrend=-1)
    private var halfCycles = 0     // ogni inversione confermata; 2 = 1 oscillazione completa

    // Rumore di fondo osservato mentre currentTrend == 0 (nessun movimento ancora
    // riconosciuto) — vedi PumpingLiveDiagnostics.restNoiseAmplitudeSpan.
    private var restMin = Float.MAX_VALUE
    private var restMax = -Float.MAX_VALUE

    // ATTENZIONE — dichiarato apertamente, non nascosto: QUESTO VALORE RESTA UN
    // PLACEHOLDER, ora rivisto con un dato reale ma non ancora confermato punto per
    // punto. Il primo giro (5.0f) era troppo alto: dai valori "Energia" osservati
    // nello status periodico (~0,5–1,8 sulla stessa scala del delta IQ usato qui),
    // nessuna singola pompata oscilla di 5 unità, quindi currentTrend restava
    // bloccato a 0 anche con pumping reale ("Pompaggio insufficiente" confermato).
    // Riportato a un ordine di grandezza compatibile con quello span (0.3f), ma va
    // confermato: nel prossimo test leggi restNoiseAmplitudeSpan nei log (ora
    // stampato dal ViewModel durante il pumping) e imposta questo campo a 2-3 volte
    // il valore massimo osservato a bobina ferma.
    var movementThreshold = 0.3f

    fun startAccumulation() {
        samples.clear()
        runningSumI = 0.0
        runningSumQ = 0.0
        amplitudeHistory.clear()
        oscillationCount = 0
        halfCycles = 0
        hasLastAmplitude = false
        currentTrend = 0
        extremum = 0f
        restMin = Float.MAX_VALUE
        restMax = -Float.MAX_VALUE
    }

    /**
     * Aggiunge un campione e restituisce una diagnostica provvisoria.
     * Ora il pumping non è più "a scatola chiusa": la UI può mostrare
     * progresso, PCA stimato, oscillazioni e ampiezza in tempo reale.
     */
    fun addSample(sample: IQVector): PumpingLiveDiagnostics {
        samples.addLast(sample)
        runningSumI += sample.i
        runningSumQ += sample.q
        if (samples.size > maxSamplesWindow) {
            val removed = samples.removeFirst()
            runningSumI -= removed.i
            runningSumQ -= removed.q
        }
        val n = samples.size

        // Centroide corrente (usato per il calcolo PCA/qualità, non per il conteggio
        // delle oscillazioni — vedi sotto, che usa l'ampiezza grezza), da somme
        // mantenute incrementalmente invece di un ricalcolo O(n) ad ogni campione.
        val center = IQVector((runningSumI / n).toFloat(), (runningSumQ / n).toFloat())

        var maxDist = 0f
        for (s in samples) {
            val d = hypot((s.i - center.i).toDouble(), (s.q - center.q).toDouble()).toFloat()
            if (d > maxDist) maxDist = d
        }

        // Rilevamento tendenza dell'ampiezza grezza, mediata su una finestra mobile
        // per spegnere il rumore ad alta frequenza prima ancora di guardare se sale
        // o scende (vedi commento sopra), poi confermata con isteresi (Schmitt
        // trigger) invece che con uno streak di campioni consecutivi.
        amplitudeHistory.addLast(sample.magnitude)
        if (amplitudeHistory.size > amplitudeFilterWindow) amplitudeHistory.removeFirst()

        if (amplitudeHistory.size == amplitudeFilterWindow) {
            val filteredAmplitude = amplitudeHistory.average().toFloat()

            if (!hasLastAmplitude) {
                extremum = filteredAmplitude
                hasLastAmplitude = true
            } else {
                when (currentTrend) {
                    0 -> {
                        // STATO INIZIALE / A RIPOSO: in attesa della prima vera mossa
                        // oltre movementThreshold. Tracciamo qui lo span di rumore
                        // reale, utile per tarare movementThreshold (vedi campo).
                        if (filteredAmplitude < restMin) restMin = filteredAmplitude
                        if (filteredAmplitude > restMax) restMax = filteredAmplitude

                        if (filteredAmplitude > extremum + movementThreshold) {
                            currentTrend = 1
                            extremum = filteredAmplitude
                        } else if (filteredAmplitude < extremum - movementThreshold) {
                            currentTrend = -1
                            extremum = filteredAmplitude
                        }
                    }
                    1 -> {
                        // IN SALITA: inseguiamo il picco.
                        if (filteredAmplitude > extremum) {
                            extremum = filteredAmplitude
                        } else if (filteredAmplitude < extremum - movementThreshold) {
                            // Inversione confermata: sceso dal picco oltre soglia.
                            currentTrend = -1
                            extremum = filteredAmplitude
                            halfCycles++
                            if (halfCycles % 2 == 0) oscillationCount++
                        }
                    }
                    else -> {
                        // IN DISCESA: inseguiamo la valle.
                        if (filteredAmplitude < extremum) {
                            extremum = filteredAmplitude
                        } else if (filteredAmplitude > extremum + movementThreshold) {
                            // Inversione confermata: salito dalla valle oltre soglia.
                            currentTrend = 1
                            extremum = filteredAmplitude
                            halfCycles++
                            if (halfCycles % 2 == 0) oscillationCount++
                        }
                    }
                }
            }
        }

        // PCA provvisorio per la UI — su una finestra più grande (1000 vs 150 di
        // prima) ricalcolarla ogni 5 campioni sarebbe costoso, ora ogni 25 a partire
        // da 100 campioni.
        val provisionalPca = if (n >= 100 && n % 25 == 0) computeProvisionalPca(center) else -1f

        val restSpan = if (restMax >= restMin) (restMax - restMin) else 0f

        return PumpingLiveDiagnostics(
            sampleCount = n,
            samplesRequired = minSamplesRequired,
            provisionalPcaQuality = provisionalPca,
            oscillationCount = oscillationCount,
            currentAmplitude = maxDist,
            center = center,
            restNoiseAmplitudeSpan = restSpan
        )
    }

    /**
     * Calcola il rapporto autovalori (PCA) sui campioni accumulati finora.
     * Usato per la diagnostica provvisoria.
     */
    private fun computeProvisionalPca(center: IQVector): Float {
        var varI = 0f
        var varQ = 0f
        var covIQ = 0f
        for (s in samples) {
            val dI = s.i - center.i
            val dQ = s.q - center.q
            varI += dI * dI
            varQ += dQ * dQ
            covIQ += dI * dQ
        }
        val tr = varI + varQ
        val disc = sqrt((varI - varQ) * (varI - varQ) + 4f * covIQ * covIQ)
        val l1 = (tr + disc) / 2f
        val l2 = (tr - disc) / 2f
        return if (l2 > 1e-9f) l1 / l2 else 100f
    }

    // ===== CALCOLO FINALE (con taglio delle ali per residuo, non per ampiezza) =====

    fun computeCalibration(): GroundCalibrationReport {
        val n = samples.size
        if (n < minSamplesRequired) {
            return GroundCalibrationReport(
                success = false,
                groundAngleDeg = 0f,
                centerPhaseDeg = 0f,
                groundCenter = IQVector.ZERO,
                pcaQuality = 0f,
                tangentialRMS = 0f,
                tangentialSigma = 0f,
                message = "Campioni insufficienti ($n/$minSamplesRequired)."
            )
        }

        // --- PRIMA PASSATA: PCA grezza, su TUTTI i campioni della finestra ---
        var sumI = 0.0
        var sumQ = 0.0
        for (s in samples) { sumI += s.i; sumQ += s.q }
        val initialCenter = IQVector((sumI / n).toFloat(), (sumQ / n).toFloat())

        var varI = 0f
        var varQ = 0f
        var covIQ = 0f
        for (s in samples) {
            val dI = s.i - initialCenter.i
            val dQ = s.q - initialCenter.q
            varI += dI * dI
            varQ += dQ * dQ
            covIQ += dI * dQ
        }
        val discInit = sqrt((varI - varQ) * (varI - varQ) + 4f * covIQ * covIQ)
        val initialAngleRad = atan2(2f * covIQ, (varI - varQ) + discInit)

        // --- TAGLIO DELLE ALI: per residuo ortogonale dalla retta, non per ampiezza ---
        // I campioni più lontani dal centroide (i picchi di ogni pompata) sono quelli
        // che portano più informazione sulla direzione dell'asse — scartarli per
        // ampiezza butterebbe via il segnale buono. Un vero outlier (glitch EMI,
        // tremore laterale) si riconosce invece da una deviazione ORTOGONALE grande
        // rispetto alla retta appena stimata: si scarta quello, non i picchi.
        val gxInit = cos(initialAngleRad)
        val gyInit = sin(initialAngleRad)
        val scored = samples.map { s ->
            val compI = s.i - initialCenter.i
            val compQ = s.q - initialCenter.q
            val orthogonalDist = abs(compI * (-gyInit) + compQ * gxInit)
            s to orthogonalDist
        }
        // Scarta il 15% con la deviazione ortogonale più alta. Percentuale da
        // convenzione statistica comune (trimmed estimator), non misurata su questo
        // hardware — dichiarato apertamente, da validare sul campo.
        val retentionCount = (n * 0.85f).toInt().coerceAtLeast(minSamplesRequired / 2)
        val filtered = scored.sortedBy { it.second }.take(retentionCount).map { it.first }
        val finalN = filtered.size

        // --- SECONDA PASSATA: PCA pulita, solo sui campioni rimasti ---
        sumI = 0.0
        sumQ = 0.0
        for (s in filtered) { sumI += s.i; sumQ += s.q }
        val center = IQVector((sumI / finalN).toFloat(), (sumQ / finalN).toFloat())

        varI = 0f; varQ = 0f; covIQ = 0f
        for (s in filtered) {
            val dI = s.i - center.i
            val dQ = s.q - center.q
            varI += dI * dI
            varQ += dQ * dQ
            covIQ += dI * dQ
        }
        val tr = varI + varQ
        val disc = sqrt((varI - varQ) * (varI - varQ) + 4f * covIQ * covIQ)
        val l1 = (tr + disc) / 2f
        val l2 = (tr - disc) / 2f
        val pcaQuality = if (l2 > 1e-9f) l1 / l2 else 100f

        val angleRad = atan2(2f * covIQ, (varI - varQ) + disc)
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // Residui tangenziali — calcolati SOLO sul set già ripulito, coerente con
        // l'asse appena ricalcolato su quello stesso set.
        val gx = cos(angleRad)
        val gy = sin(angleRad)
        var sumSq = 0f
        val tangValues = ArrayList<Float>(finalN)
        for (s in filtered) {
            val compI = s.i - center.i
            val compQ = s.q - center.q
            val tangential = compI * (-gy) + compQ * gx
            tangValues.add(abs(tangential))
            sumSq += tangential * tangential
        }
        val tangentialRMS = sqrt(sumSq / finalN)
        val meanTang = tangValues.average().toFloat()
        var varianceTang = 0f
        for (v in tangValues) { varianceTang += (v - meanTang) * (v - meanTang) }
        val tangentialSigma = sqrt(varianceTang / finalN)

        // Validazione — include il numero di oscillazioni REALI (dal rilevamento di
        // tendenza in addSample()), non solo la qualità statistica della retta: una
        // retta può risultare statisticamente "buona" anche con un solo movimento in
        // una direzione senza mai invertire, il che non è un vero pumping.
        val success = pcaQuality >= 8.0f &&
                tangentialSigma <= tangentialRMS * 0.8f &&
                oscillationCount >= minOscillationsRequired
        // FIX (messaggio diagnostico incoerente con la soglia reale): il gate di
        // successo richiede pcaQuality >= 8.0f, ma il ramo diagnostico usava ancora
        // 3.0f (soglia di un giro precedente, mai allineata quando il gate fu portato
        // a 8.0f). Risultato: con pcaQuality tra 3.0 e 8.0 — quindi comunque sotto il
        // gate reale, calibrazione rifiutata — l'utente vedeva il messaggio "Sigma"
        // anche quando il sigma rispettava tranquillamente la soglia, e la causa vera
        // del rifiuto (PCA insufficiente) non veniva mai comunicata in quella fascia.
        //
        // PCA è la misura diretta di quanto ci si può fidare dell'asse appena
        // calcolato (rapporto tra autovalori: quanto la nuvola di campioni è
        // allungata lungo una direzione dominante). Un valore basso non indica
        // terreno "difficile": indica che la pompata non è stata pulita (movimento
        // laterale, bobina non parallela al terreno, un target sotto la bobina
        // durante il pumping, tremore) — la retta non è ben definita. Sotto 3.0 è
        // probabile un disturbo attivo (target, forte interferenza); tra 3.0 e 8.0 è
        // più probabile un gesto imperfetto ma altrimenti pulito — messaggi distinti
        // per guidare l'utente verso l'azione corretta in ciascun caso.
        val message = if (success) {
            "✅ Calibrazione riuscita! Angolo = ${"%.1f".format(angleDeg)}° (PCA = ${"%.1f".format(pcaQuality)}, $oscillationCount pompate)"
        } else {
            if (oscillationCount < minOscillationsRequired) {
                "❌ Pompaggio insufficiente ($oscillationCount/$minOscillationsRequired pompate reali)."
            } else if (pcaQuality < 3.0f) {
                "❌ Qualità PCA molto bassa (${"%.1f".format(pcaQuality)}). Possibile target o forte disturbo sotto la bobina: sposta in una zona pulita e ripeti il Pumping."
            } else if (pcaQuality < 8.0f) {
                "❌ Qualità PCA insufficiente (${"%.1f".format(pcaQuality)}, richiesto ≥8.0). Pompata non pulita (movimento laterale, bobina non parallela, tremore): ripeti con un gesto verticale più regolare."
            } else {
                "❌ Pompaggio irregolare (Sigma: %.3f > RMS*0.8: %.3f).".format(tangentialSigma, tangentialRMS * 0.8f)
            }
        }

        val centerPhaseDeg = Math.toDegrees(atan2(center.q, center.i).toDouble()).toFloat()
        return GroundCalibrationReport(
            success = success,
            groundAngleDeg = angleDeg,
            centerPhaseDeg = centerPhaseDeg,
            groundCenter = center,
            pcaQuality = pcaQuality,
            tangentialRMS = tangentialRMS,
            tangentialSigma = tangentialSigma,
            message = message
        )
    }
}