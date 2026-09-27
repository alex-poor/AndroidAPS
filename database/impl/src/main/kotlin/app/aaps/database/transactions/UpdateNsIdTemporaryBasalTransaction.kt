package app.aaps.database.transactions

import app.aaps.database.entities.TemporaryBasal

class UpdateNsIdTemporaryBasalTransaction(private val temporaryBasals: List<TemporaryBasal>) : Transaction<UpdateNsIdTemporaryBasalTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (temporaryBasal in temporaryBasals) {
            val current = database.temporaryBasalDao.findById(temporaryBasal.id)
            if (current != null && current.interfaceIDs.nightscoutId != temporaryBasal.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = temporaryBasal.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.temporaryBasalDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<TemporaryBasal>()
    }
}