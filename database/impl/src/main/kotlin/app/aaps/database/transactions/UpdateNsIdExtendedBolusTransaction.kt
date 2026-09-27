package app.aaps.database.transactions

import app.aaps.database.entities.ExtendedBolus

class UpdateNsIdExtendedBolusTransaction(val boluses: List<ExtendedBolus>) : Transaction<UpdateNsIdExtendedBolusTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (bolus in boluses) {
            val current = database.extendedBolusDao.findById(bolus.id)
            if (current != null && current.interfaceIDs.nightscoutId != bolus.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = bolus.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.extendedBolusDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<ExtendedBolus>()
    }
}