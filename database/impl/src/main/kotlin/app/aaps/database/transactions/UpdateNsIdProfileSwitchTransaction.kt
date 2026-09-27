package app.aaps.database.transactions

import app.aaps.database.entities.ProfileSwitch

class UpdateNsIdProfileSwitchTransaction(val profileSwitches: List<ProfileSwitch>) : Transaction<UpdateNsIdProfileSwitchTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (profileSwitch in profileSwitches) {
            val current = database.profileSwitchDao.findById(profileSwitch.id)
            if (current != null && current.interfaceIDs.nightscoutId != profileSwitch.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = profileSwitch.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.profileSwitchDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<ProfileSwitch>()
    }
}