package app.aaps.database.transactions

import app.aaps.database.entities.TherapyEvent

class UpdateNsIdTherapyEventTransaction(val therapyEvents: List<TherapyEvent>) : Transaction<UpdateNsIdTherapyEventTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (therapyEvent in therapyEvents) {
            val current = database.therapyEventDao.findById(therapyEvent.id)
            if (current != null && current.interfaceIDs.nightscoutId != therapyEvent.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = therapyEvent.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.therapyEventDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<TherapyEvent>()
    }
}