package com.tetranova.prerex

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rilevamento del movimento della bobina utilizzando sensori fisici.
 *
 * FIX ARCHITETTURALE: la versione precedente usava soglie assolute fisse
 * (THRESHOLD_ON = 0.35 rad/s, THRESHOLD_OFF = 0.15 rad/s), tarate a tavolino su un singolo
 * test. Nella pratica la velocità di spazzolamento cambia con il terreno, lo stile di ricerca
 * e persino con l'umore dello stesso utente nella stessa sessione: una soglia assoluta o è
 * troppo alta (una ricerca "low & slow" non viene mai vista come movimento) o troppo bassa
 * (una spazzolata rapida su terreno vibrante genera falsi trigger).
 *
 * Sostituita con un trigger STA/LTA (Short-Term Average / Long-Term Average), tecnica
 * consolidata in sismologia (rilevazione eventi) e VAD (voice activity detection): si
 * confronta un'energia "istantanea" (STA) con un'energia di fondo a lungo termine (LTA) che
 * rappresenta il rumore ambientale corrente (vibrazione dell'asta, tremore della mano,
 * terreno). Il trigger scatta sul RAPPORTO STA/LTA, non su un valore assoluto: si
 * auto-normalizza rispetto al rumore di fondo corrente, quindi si adatta da solo a stile di
 * spazzolata, terreno e dispositivo, senza bisogno di ritarare le costanti manualmente.
 *
 * L'LTA viene aggiornato a velocità ridotta MENTRE si è in movimento: altrimenti una
 * spazzolata lunga e continua "insegnerebbe" al proprio rumore di fondo che quel livello è la
 * norma, facendo collassare il rapporto verso 1 e disattivando il trigger da solo (problema
 * classico di drift dello STA/LTA sugli eventi di lunga durata).
 *
 * Strategia Ibrida:
 * 1. Giroscopio (primario) - STA/LTA su modulo velocità angolare
 * 2. Accelerometro (fallback) - STA/LTA sulla varianza dell'accelerazione
 * 3. Fallback software - basato sulla varianza del segnale IQ (se nessun sensore)
 *
 * Compatibile con:
 * - Huawei P30 Pro (giroscopio, accelerometro)
 * - OnePlus Nord 2 5G (giroscopio, accelerometro)
 */
class GroundMotionDetector(context: Context) : SensorEventListener {

    // Callback per notificare il GroundTracker
    var onMotionStateChanged: ((Boolean) -> Unit)? = null

    // Sensori
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Stato del movimento
    private var isMoving = false

    // =============================================================
    // FIX: TRIGGER ADATTIVO STA/LTA (sostituisce le vecchie soglie assolute fisse)
    // =============================================================

    /** Costante di smoothing dello STA (energia a breve termine). Rimpiazza il vecchio FILTER_ALPHA. */
    var staAlpha = 0.4f

    /** Costante di adattamento dell'LTA da FERMO: si adegua al rumore di fondo corrente
     *  (terreno, stile, mano) in circa 1/ltaAlphaStationary campioni (~2s @ 50Hz). */
    var ltaAlphaStationary = 0.01f

    /** Costante di adattamento dell'LTA IN MOVIMENTO: quasi congelata (~40s @ 50Hz) per non
     *  far collassare il rapporto durante spazzolate lunghe e continue. */
    var ltaAlphaMoving = 0.0005f

    /** Rapporto STA/LTA sopra il quale si dichiara "in movimento" (isteresi alta). */
    var ratioOn = 3.0f

    /** Rapporto STA/LTA sotto il quale si dichiara "fermo" (isteresi bassa). */
    var ratioOff = 1.6f

    /** Soglia assoluta di sicurezza (rad/s): sotto questo valore non si scatta MAI il
     *  movimento, indipendentemente dal rapporto — protegge da falsi trigger quando l'LTA è
     *  vicino a zero (telefono immobile su un banco, solo rumore residuo del MEMS). */
    var absoluteMinSpeed = 0.04f

    /** Floor minimo dell'LTA per evitare rapporti numericamente degeneri (divisione per ~0). */
    private val ltaEpsilon = 0.01f

    /** Tetto massimo dell'LTA: impedisce a lta di "gonfiarsi" oltre il livello osservato
     *  durante il pumping (movimento reale e intenzionale). Spezza il ciclo di retroazione
     *  in cui spazzamenti reali mal classificati come "fermi" facevano salire lta,
     *  abbassando ulteriormente il rapporto e rendendo il rilevamento sempre più insensibile. */
    private var seededLtaCeiling = Float.MAX_VALUE

    /** Campioni di warm-up prima di fidarsi del rapporto STA/LTA (~2s @ 50Hz). Durante il
     *  warm-up l'LTA insegue rapidamente lo STA invece di decidere prematuramente lo stato. */
    var warmupSamples = 100

    // FIX: DEBOUNCE ANTI-INVERSIONE. Un'inversione di spazzolata (fine corsa, cambio di
    // verso) fa passare la velocità angolare per uno zero fisico anche durante un movimento
    // continuo: lo STA (staAlpha=0.4, molto reattivo) segue quel minimo istantaneo e il
    // rapporto STA/LTA scende sotto ratioOff per una frazione di secondo, venendo scambiato
    // per un vero arresto (osservato come "Fermo" spurio nello status periodico ad ogni
    // fine-corsa). Richiediamo che il rapporto resti sotto ratioOff per stopDebounceMs
    // consecutivi prima di dichiarare realmente fermo: un'inversione vera dura una frazione
    // di secondo e rientra prima dello scadere del debounce, un arresto reale no.
    var stopDebounceMs = 300L
    private var belowOffSinceMs = 0L

    private var sta = 0f
    private var lta = 0f
    private var sampleCounter = 0

    // =============================================================
    // FIX: DISTINZIONE BOBINA APPOGGIATA vs TENUTA IN MANO (TRUE STATIC vs MICRO-TREMOR)
    // =============================================================
    // Dati reali raccolti (vedi log condivisi):
    //   - Bobina appoggiata su tavolo: LTA ≈ 0,0004-0,0009 rad/s
    //   - Bobina tenuta in mano, ferma (tremore fisiologico): LTA ≈ 0,0155-0,0417 rad/s
    // Gap di 50-70x, nessuna sovrapposizione tra le due condizioni misurate.
    //
    // Il RAPPORTO STA/LTA non è utilizzabile per questa distinzione: è per costruzione
    // relativo al proprio rumore di fondo corrente (si autonormalizza), quindi i due casi
    // restano indistinguibili sul rapporto (0,51-1,20 in mano vs 0,05-1,5 su tavolo:
    // range sovrapposti). L'LTA ASSOLUTO invece separa bene le due condizioni: è già una
    // media lenta, quindi integra nel tempo invece di reagire al singolo campione.
    //
    // Soglia impostata a metà (scala logaritmica) tra i due cluster misurati, con margine
    // di sicurezza verso il basso: resta comodamente sotto anche il valore minimo
    // osservato in mano (0,0155), quindi nessun rischio di scambiare un tremore per
    // immobilità reale.
    var staticLtaThreshold = 0.005f

    /**
     * Vero se la bobina è FISICAMENTE immobile (appoggiata), distinto dal tremore
     * fisiologico di una mano che la tiene ferma (MICRO-TREMOR/pinpoint). Richiede il
     * giroscopio: senza un dato IMU affidabile non c'è modo sicuro di fare questa
     * distinzione, quindi ritorna sempre false in quel caso (nessun riassorbimento
     * statico autorizzato senza IMU — vedi GroundTracker.setImuStaticState()).
     */
    fun isTrueStatic(): Boolean = isGyroscopeAvailable && lta < staticLtaThreshold

    // =============================================================
    // FIX: SEEDING DA PUMPING
    // =============================================================
    // Durante il pumping l'utente sta certamente maneggiando/muovendo attivamente il
    // rilevatore: raccogliamo i campioni grezzi di velocità angolare in questa finestra per
    // ricavare, a fine pump, un rumore di fondo (LTA) di partenza specifico per questa
    // sessione/utente/dispositivo, invece dei valori generici accumulati da freddo (o
    // ereditati da un contesto pre-pump non rappresentativo, es. telefono fermo su un
    // tavolo prima di iniziare).
    private var isSeedingFromPump = false
    private val pumpSpeedSamples = mutableListOf<Float>()
    private val minPumpSamplesForSeeding = 20

    // Accelerometro (fallback) - stessa logica STA/LTA applicata alla varianza del segnale
    private val accelBuffer = mutableListOf<Float>()
    private val accelBufferSize = 10
    private var accelSta = 0f
    private var accelLta = 0f
    private var accelSampleCounter = 0

    // Fallback software (se nessun sensore disponibile)
    private var iqMovingCounter = 0
    private val IQ_MIN_CONSECUTIVE = 3

    private var isGyroscopeAvailable = gyroscope != null
    private var isAccelerometerAvailable = accelerometer != null

    init {
        if (!isGyroscopeAvailable && !isAccelerometerAvailable) {
            android.util.Log.w("GroundMotionDetector",
                "⚠️ Nessun sensore di movimento disponibile! Uso fallback software basato su segnale IQ.")
        } else {
            val sensorType = when {
                isGyroscopeAvailable -> "Giroscopio (primario, STA/LTA adattivo)"
                else -> "Accelerometro (fallback, STA/LTA adattivo)"
            }
            android.util.Log.d("GroundMotionDetector", "✅ Sensore movimento: $sensorType")
        }
    }

    fun startListening() {
        if (isGyroscopeAvailable) {
            sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME  // ~20ms (50Hz)
            )
        } else if (isAccelerometerAvailable) {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        // Reset dello stato all'avvio (incluso il trigger adattivo, che riparte in warm-up)
        isMoving = false
        sta = 0f
        lta = 0f
        sampleCounter = 0
        accelSta = 0f
        accelLta = 0f
        accelSampleCounter = 0
        accelBuffer.clear()
        isSeedingFromPump = false
        pumpSpeedSamples.clear()
        belowOffSinceMs = 0L
        // FIX (bug "500 campioni a bobina ferma"): il reset qui copriva sta/lta/sampleCounter
        // ma NON seededLtaCeiling, che quindi sopravviveva a una nuova connessione/sessione
        // di ascolto, portandosi dietro il tetto derivato da un pumping precedente (magari
        // di una sessione completamente diversa). Va resettato qui allo stesso modo.
        seededLtaCeiling = Float.MAX_VALUE
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        isMoving = false
        sta = 0f
        lta = 0f
        belowOffSinceMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            // =============================================================
            // GIROSCOPIO - Trigger adattivo STA/LTA
            // =============================================================
            Sensor.TYPE_GYROSCOPE -> {
                // Calcolo velocità angolare totale (rad/s) - modulo, indipendente
                // dall'orientamento di montaggio del telefono sull'asta
                val rawSpeed = sqrt(
                    event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                )

                // STA: media mobile a breve termine (stesso ruolo del vecchio filtro EMA)
                sta = (staAlpha * rawSpeed) + ((1 - staAlpha) * sta)
                sampleCounter++

                // FIX: durante il pumping accumuliamo il campione grezzo per il seeding,
                // senza toccare la logica decisionale del trigger (che continua a girare
                // normalmente in parallelo - il pumping non passa comunque per il
                // GroundTracker, quindi lo stato isMoving qui non ha effetti collaterali).
                if (isSeedingFromPump) {
                    pumpSpeedSamples.add(rawSpeed)
                }

                // ★ FIX: l'LTA si aggiorna a velocità diversa a seconda dello stato corrente,
                // per non "auto-avvelenarsi" durante spazzolate lunghe e continue (altrimenti
                // l'LTA insegue lo STA fino a far collassare il rapporto verso 1).
                // ★ Aggiunto coerceAtMost(seededLtaCeiling) per impedire a lta di gonfiarsi
                // oltre il livello osservato durante il pumping, spezzando il ciclo di
                // retroazione che rendeva progressivamente insensibile il rilevamento.
                val ltaAlpha = if (isMoving) ltaAlphaMoving else ltaAlphaStationary
                lta = ((ltaAlpha * sta) + ((1 - ltaAlpha) * lta)).coerceAtMost(seededLtaCeiling)

                if (sampleCounter <= warmupSamples) {
                    // Durante il warm-up lascia che l'LTA converga rapidamente al livello
                    // di rumore reale, senza dichiarare movimento prematuramente
                    if (lta < ltaEpsilon) lta = max(lta, sta * 0.5f)
                    return
                }

                val safeLta = max(lta, ltaEpsilon)
                val ratio = sta / safeLta

                // Isteresi sul RAPPORTO (auto-normalizzato al rumore di fondo corrente),
                // con un floor assoluto di sicurezza per evitare trigger su rumore puro
                if (!isMoving && ratio > ratioOn && sta > absoluteMinSpeed) {
                    isMoving = true
                    belowOffSinceMs = 0L
                    onMotionStateChanged?.invoke(true)
                } else if (isMoving && ratio < ratioOff) {
                    // FIX: vedi commento su stopDebounceMs sopra. Non dichiariamo subito
                    // fermo al primo campione sotto ratioOff: aspettiamo che ci resti per
                    // stopDebounceMs consecutivi, per non scambiare un'inversione di
                    // spazzolata per un vero arresto.
                    val nowMs = System.currentTimeMillis()
                    if (belowOffSinceMs == 0L) {
                        belowOffSinceMs = nowMs
                    } else if (nowMs - belowOffSinceMs >= stopDebounceMs) {
                        isMoving = false
                        belowOffSinceMs = 0L
                        onMotionStateChanged?.invoke(false)
                    }
                } else {
                    // Il rapporto è tornato sopra ratioOff prima che scadesse il debounce
                    // (o non era mai sceso): era un'inversione, non un arresto. Annulla
                    // l'eventuale timer pendente.
                    belowOffSinceMs = 0L
                }
                // Se isMoving è true e il rapporto è tra ratioOff e ratioOn, lo stato
                // rimane invariato (ISTERESI, come nella versione precedente)
            }

            // =============================================================
            // ACCELEROMETRO - Fallback, stessa logica STA/LTA sulla varianza
            // =============================================================
            Sensor.TYPE_ACCELEROMETER -> {
                val accelMagnitude = sqrt(
                    event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                )

                accelBuffer.add(accelMagnitude)
                if (accelBuffer.size > accelBufferSize) {
                    accelBuffer.removeAt(0)
                }

                if (accelBuffer.size >= accelBufferSize) {
                    val mean = accelBuffer.average().toFloat()
                    val variance = accelBuffer.map { (it - mean) * (it - mean) }.average().toFloat()

                    accelSampleCounter++
                    accelSta = variance

                    // ★ Applicata la stessa limitazione al tetto per il fallback accelerometro
                    val ltaAlpha = if (isMoving) ltaAlphaMoving else ltaAlphaStationary
                    accelLta = ((ltaAlpha * accelSta) + ((1 - ltaAlpha) * accelLta)).coerceAtMost(seededLtaCeiling)

                    if (accelSampleCounter <= warmupSamples) {
                        if (accelLta < ltaEpsilon) accelLta = max(accelLta, accelSta * 0.5f)
                        return
                    }

                    val safeLta = max(accelLta, ltaEpsilon)
                    val ratio = accelSta / safeLta

                    // FIX: stesso debounce anti-inversione del percorso giroscopio (vedi
                    // stopDebounceMs), applicato qui per coerenza nel caso di fallback.
                    val nowMs = System.currentTimeMillis()
                    if (!isMoving && ratio > ratioOn) {
                        isMoving = true
                        belowOffSinceMs = 0L
                        onMotionStateChanged?.invoke(true)
                    } else if (isMoving && ratio < ratioOff) {
                        if (belowOffSinceMs == 0L) {
                            belowOffSinceMs = nowMs
                        } else if (nowMs - belowOffSinceMs >= stopDebounceMs) {
                            isMoving = false
                            belowOffSinceMs = 0L
                            onMotionStateChanged?.invoke(false)
                        }
                    } else {
                        belowOffSinceMs = 0L
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Non necessario per il nostro caso d'uso
    }

    // =============================================================
    // FALLBACK SOFTWARE - Basato sul segnale IQ (quando no sensori)
    // =============================================================
    fun fallbackMotionDetection(signalVariance: Float) {
        if (isGyroscopeAvailable || isAccelerometerAvailable) return

        // Rilevamento basato sulla varianza del segnale IQ
        val isCurrentlyMoving = signalVariance > 0.01f

        if (isCurrentlyMoving) {
            iqMovingCounter = min(iqMovingCounter + 1, IQ_MIN_CONSECUTIVE + 1)
            if (iqMovingCounter >= IQ_MIN_CONSECUTIVE && !isMoving) {
                isMoving = true
                onMotionStateChanged?.invoke(true)
            }
        } else {
            iqMovingCounter = max(iqMovingCounter - 1, 0)
            if (iqMovingCounter == 0 && isMoving) {
                isMoving = false
                onMotionStateChanged?.invoke(false)
            }
        }
    }

    fun isDeviceMoving(): Boolean = isMoving

    /** Vero se è disponibile almeno un sensore hardware di movimento (giroscopio o
     *  accelerometro). Usato dal ViewModel per decidere se instradare il fallback software
     *  basato sulla varianza del segnale IQ. */
    fun hasHardwareSensor(): Boolean = isGyroscopeAvailable || isAccelerometerAvailable

    // =============================================================
    // FIX: SEEDING DA PUMPING - API pubblica
    // =============================================================

    /** Avvia la finestra di raccolta campioni per il seeding del rumore di fondo.
     *  Da chiamare all'inizio del pumping (startGroundCalibration). */
    fun beginPumpSeeding() {
        isSeedingFromPump = true
        pumpSpeedSamples.clear()
        // FIX (bug "500 campioni a bobina ferma", causa radice): seededLtaCeiling non
        // veniva MAI ripulito tra un tentativo di pumping e il successivo. Il tetto
        // restava quindi quello derivato dall'ULTIMO pumping riuscito (o anche solo
        // tentato), che tipicamente contiene un istante di velocità angolare quasi
        // nulla (l'inversione del gesto di pompata) — quel valore basso, una volta
        // fissato come tetto permanente su lta, rendeva lta artificialmente più basso
        // del rumore vero anche a bobina ferma. Risultato: sta/lta sfondava ratioOn
        // quasi subito in OGNI test successivo, isMoving restava bloccato a true, e il
        // gate di movimento accettava campioni indefinitamente anche da fermo.
        // Azzerandolo qui, ogni nuovo tentativo di pumping riparte senza tetto
        // ereditato: verrà ri-derivato da zero, sui dati di QUESTO tentativo, da
        // endPumpSeeding() a fine pump.
        seededLtaCeiling = Float.MAX_VALUE
    }

    /** Chiude la finestra di raccolta e, se sono stati raccolti abbastanza campioni,
     *  inizializza l'LTA con un valore derivato da dati reali di questa sessione invece che
     *  dal default generico o dal contesto pre-pump. Va chiamata SEMPRE a fine pumping,
     *  sia in caso di successo che di fallimento/interruzione della calibrazione del
     *  terreno, altrimenti la finestra resta aperta indefinitamente. Sicura da chiamare
     *  più volte o senza una beginPumpSeeding() precedente (no-op in quel caso). */
    fun endPumpSeeding() {
        if (!isSeedingFromPump) return
        isSeedingFromPump = false

        if (pumpSpeedSamples.size < minPumpSamplesForSeeding) {
            // Campione insufficiente (pump troppo breve/interrotto subito): non seedare,
            // mantieni lo stato adattivo corrente invece di inizializzarlo con rumore.
            android.util.Log.w("GroundMotionDetector",
                "⚠️ Seeding da pumping saltato: solo ${pumpSpeedSamples.size} campioni raccolti")
            pumpSpeedSamples.clear()
            return
        }

        val sorted = pumpSpeedSamples.sorted()
        // 20° percentile: rappresenta i momenti "più fermi" durante il pumping (es. le
        // inversioni del movimento verticale, dove la velocità angolare passa per un
        // minimo) - un proxy realistico del rumore di fondo di QUESTO utente e QUESTO
        // dispositivo, invece di un valore a tavolino uguale per tutti.
        val idx20 = (sorted.size * 0.20f).toInt().coerceIn(0, sorted.size - 1)
        val idx80 = (sorted.size * 0.80f).toInt().coerceIn(0, sorted.size - 1)
        val noiseFloorEstimate = sorted[idx20]
        val activeLevelEstimate = sorted[idx80]

        lta = max(noiseFloorEstimate, ltaEpsilon)
        // ★ FIX: imposta il tetto massimo per l'LTA al livello appena seminato, impedendo
        // che lta possa gonfiarsi oltre il rumore osservato durante un movimento reale e
        // intenzionale (il pumping), spezzando il ciclo di retroazione che rendeva il
        // rilevamento sempre più insensibile nel corso della sessione.
        seededLtaCeiling = lta
        sta = lta
        sampleCounter = warmupSamples + 1 // salta il warm-up: abbiamo già dati reali

        android.util.Log.d("GroundMotionDetector",
            "🌱 Seeding da pumping completato: rumore=%.4f rad/s | attivo=%.4f rad/s | tetto_LTA=%.4f | campioni=%d".format(
                noiseFloorEstimate, activeLevelEstimate, seededLtaCeiling, sorted.size
            ))

        pumpSpeedSamples.clear()
    }

    // =============================================================
    // DIAGNOSTICA - Per debug
    // =============================================================
    fun getSensorStatus(): String {
        return when {
            isGyroscopeAvailable -> "✅ Giroscopio (STA/LTA adattivo)"
            isAccelerometerAvailable -> "⚠️ Accelerometro (STA/LTA adattivo, fallback)"
            else -> "⚠️ Software (fallback IQ)"
        }
    }

    /** FIX: nuovo metodo diagnostico - espone STA, LTA e rapporto correnti, utile per
     *  loggare/verificare il comportamento del trigger adattivo con dati reali invece di
     *  ipotesi teoriche. */
    fun getAdaptiveDiagnostics(): MotionDiagnostics {
        val safeLta = max(lta, ltaEpsilon)
        return MotionDiagnostics(
            sta = sta,
            lta = lta,
            ratio = sta / safeLta,
            ratioOn = ratioOn,
            ratioOff = ratioOff,
            isCalibrated = sampleCounter > warmupSamples,
            isMoving = isMoving
        )
    }

    data class MotionDiagnostics(
        val sta: Float,
        val lta: Float,
        val ratio: Float,
        val ratioOn: Float,
        val ratioOff: Float,
        val isCalibrated: Boolean,
        val isMoving: Boolean
    )
}