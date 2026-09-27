package app.aaps.plugins.aps.camaps

/**
 * The only two things [CamapsMpc] needs of a plant, so the controller can roll out either the Hovorka
 * chassis or CamAPS's own [CamapsSubModel] without knowing which it has.
 */
interface ControlModel {
    fun glucoseMmol(s: DoubleArray): Double
    fun step(s: DoubleArray, u: Double, dtMin: Double): DoubleArray
}

/** Adapts the Hovorka-family models, which already have these signatures. */
class HovorkaControlModel(private val m: app.aaps.plugins.aps.hovorka.HovorkaModel) : ControlModel {
    override fun glucoseMmol(s: DoubleArray) = m.glucoseMmol(s)
    override fun step(s: DoubleArray, u: Double, dtMin: Double) = m.step(s, u, dtMin)
}
