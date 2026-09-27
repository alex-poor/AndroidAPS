package app.aaps.database.transactions

import app.aaps.database.entities.GlucoseValue

class UpdateNsIdGlucoseValueTransaction(private val glucoseValues: List<GlucoseValue>) : Transaction<UpdateNsIdGlucoseValueTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (glucoseValue in glucoseValues) {
            val current = database.glucoseValueDao.findById(glucoseValue.id)
            if (current != null && current.interfaceIDs.nightscoutId != glucoseValue.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = glucoseValue.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.glucoseValueDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<GlucoseValue>()
    }
}