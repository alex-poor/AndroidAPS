package app.aaps.database.transactions

import app.aaps.database.entities.BolusCalculatorResult

class UpdateNsIdBolusCalculatorResultTransaction(private val bolusCalculatorResults: List<BolusCalculatorResult>) : Transaction<UpdateNsIdBolusCalculatorResultTransaction.TransactionResult>() {

    val result = TransactionResult()
    override fun run(): TransactionResult {
        for (bolusCalculatorResult in bolusCalculatorResults) {
            val current = database.bolusCalculatorResultDao.findById(bolusCalculatorResult.id)
            if (current != null && current.interfaceIDs.nightscoutId != bolusCalculatorResult.interfaceIDs.nightscoutId) {
                current.interfaceIDs.nightscoutId = bolusCalculatorResult.interfaceIDs.nightscoutId
                // In-place: adding the NS id is bookkeeping, not an edit. updateExistingEntry would write a full history
                // copy (which NS sync then skips as "only NS id changed") and fire a data-change event that re-runs IOB/COB.
                database.bolusCalculatorResultDao.update(current)
                result.updatedNsId.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<BolusCalculatorResult>()
    }
}