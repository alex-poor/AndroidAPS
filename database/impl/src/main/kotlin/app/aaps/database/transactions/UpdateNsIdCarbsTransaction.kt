package app.aaps.database.transactions

import app.aaps.database.entities.Carbs

class UpdateNsIdCarbsTransaction(private val carbs: List<Carbs>) : Transaction<UpdateNsIdCarbsTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (carb in carbs) {
            val current = database.carbsDao.findById(carb.id)
            if (current != null && current.interfaceIDs.nightscoutId != carb.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = carb.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.carbsDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<Carbs>()
    }
}