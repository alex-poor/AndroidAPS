package app.aaps.database.transactions

import app.aaps.database.entities.EffectiveProfileSwitch

class UpdateNsIdEffectiveProfileSwitchTransaction(private val effectiveProfileSwitches: List<EffectiveProfileSwitch>) : Transaction<UpdateNsIdEffectiveProfileSwitchTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (effectiveProfileSwitch in effectiveProfileSwitches) {
            val current = database.effectiveProfileSwitchDao.findById(effectiveProfileSwitch.id)
            if (current != null && current.interfaceIDs.nightscoutId != effectiveProfileSwitch.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = effectiveProfileSwitch.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.effectiveProfileSwitchDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<EffectiveProfileSwitch>()
    }
}