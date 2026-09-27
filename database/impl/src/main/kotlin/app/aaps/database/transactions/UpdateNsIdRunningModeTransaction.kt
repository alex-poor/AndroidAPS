package app.aaps.database.transactions

import app.aaps.database.entities.RunningMode

class UpdateNsIdRunningModeTransaction(val runningModes: List<RunningMode>) : Transaction<UpdateNsIdRunningModeTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (runningMode in runningModes) {
            val current = database.runningModeDao.findById(runningMode.id)
            if (current != null && current.interfaceIDs.nightscoutId != runningMode.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = runningMode.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.runningModeDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<RunningMode>()
    }
}