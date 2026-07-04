package app.aaps.plugins.main.profile.ui

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DecimalFormat

/**
 * Headless port of the array-manipulation semantics of [TimeListEdit] (editItem / addItem /
 * removeItem / value1 / value2 / secondFromMidnight), extracted VERBATIM so the Compose
 * [app.aaps.plugins.main.profile.compose.ProfileEditor] edits the profile JSON byte-for-byte
 * identically to the legacy editor.
 *
 * SAFETY-CRITICAL: this edits basal/ISF/IC/target arrays. Every method below mirrors the matching
 * private method in [TimeListEdit] exactly (same JSONObject keys "time"/"timeAsSeconds"/"value",
 * same "HH:00" formatting, same downTo shift loop, same clamps). Do NOT change behaviour here
 * without changing [TimeListEdit] in lock-step.
 *
 * `data2` is the second array of a PAIR (used only by TARGET: data1=targetLow, data2=targetHigh);
 * pass null for the single-array categories (basal/ISF/IC).
 */
object ProfileBlockOps {

    private const val ONE_HOUR_IN_SECONDS = 60 * 60

    /** == TimeListEdit.itemsCount() */
    fun itemsCount(data1: JSONArray): Int = data1.length()

    /** == TimeListEdit.value1() */
    fun value1(data1: JSONArray, index: Int): Double {
        try {
            val item = data1[index] as JSONObject
            if (item.has("value")) return item.getDouble("value")
        } catch (_: JSONException) {
        }
        return 0.0
    }

    /** == TimeListEdit.value2() */
    fun value2(data2: JSONArray?, index: Int): Double {
        if (data2 != null) {
            try {
                val item = data2[index] as JSONObject
                if (item.has("value")) return item.getDouble("value")
            } catch (_: JSONException) {
            }
        }
        return 0.0
    }

    /**
     * == TimeListEdit.secondFromMidnight() — including the "every array must start with 0" fix:
     * if index 0 has a non-zero timeAsSeconds it is rewritten to 0 in place.
     */
    fun secondFromMidnight(data1: JSONArray, index: Int): Int {
        try {
            val item = data1[index] as JSONObject
            if (item.has("timeAsSeconds")) {
                var time = item.getInt("timeAsSeconds")
                if (index == 0 && time != 0) {
                    // fix the bug, every array must start with 0
                    item.put("timeAsSeconds", 0)
                    time = 0
                }
                return time
            }
        } catch (_: JSONException) {
        }
        return 0
    }

    /** == TimeListEdit.editItem() — writes both data1 and (if present) data2 with the "HH:00" label. */
    fun editBlock(data1: JSONArray, data2: JSONArray?, index: Int, timeAsSeconds: Int, value1: Double, value2: Double) {
        try {
            val hour = timeAsSeconds / 60 / 60
            val df = DecimalFormat("00")
            val time = df.format(hour.toLong()) + ":00"
            val newObject1 = JSONObject()
            newObject1.put("time", time)
            newObject1.put("timeAsSeconds", timeAsSeconds)
            newObject1.put("value", value1)
            data1.put(index, newObject1)
            if (data2 != null) {
                val newObject2 = JSONObject()
                newObject2.put("time", time)
                newObject2.put("timeAsSeconds", timeAsSeconds)
                newObject2.put("value", value2)
                data2.put(index, newObject2)
            }
        } catch (_: JSONException) {
        }
    }

    /**
     * == TimeListEdit.addItem() — the downTo shift loop then editItem(index, ..., 0.0, 0.0).
     * (The View-only inflateRow bookkeeping from the original is intentionally omitted.)
     */
    fun addBlock(data1: JSONArray, data2: JSONArray?, index: Int, timeAsSeconds: Int) {
        if (itemsCount(data1) >= 24) return
        try {
            // shift data
            for (i in data1.length() downTo index + 1) {
                data1.put(i, data1[i - 1])
                data2?.put(i, data2[i - 1])
            }
            // add new object
            editBlock(data1, data2, index, timeAsSeconds, 0.0, 0.0)
        } catch (_: JSONException) {
        }
    }

    /** == TimeListEdit.removeItem() */
    fun removeBlock(data1: JSONArray, data2: JSONArray?, index: Int) {
        data1.remove(index)
        data2?.remove(index)
    }

    /**
     * The "final add" button behaviour from [TimeListEdit.buildView]: append a block one hour after
     * the last (or at 0 if empty).
     */
    fun appendBlock(data1: JSONArray, data2: JSONArray?) {
        val count = itemsCount(data1)
        addBlock(data1, data2, count, if (count > 0) secondFromMidnight(data1, count - 1) + ONE_HOUR_IN_SECONDS else 0)
    }

    /**
     * The per-row "+" (insert-after) behaviour from [TimeListEdit.inflateRow], including the
     * post-insert re-spacing of following rows and the 24-item / 23:00 trailing clamp.
     */
    fun insertAfter(data1: JSONArray, data2: JSONArray?, position: Int) {
        val seconds = secondFromMidnight(data1, position)
        addBlock(data1, data2, position, seconds)
        // push following values forward so times stay strictly increasing
        for (i in position + 1 until itemsCount(data1)) {
            if (secondFromMidnight(data1, i - 1) >= secondFromMidnight(data1, i)) {
                editBlock(data1, data2, i, secondFromMidnight(data1, i - 1) + ONE_HOUR_IN_SECONDS, value1(data1, i), value2(data2, i))
            }
        }
        while (itemsCount(data1) > 24 || secondFromMidnight(data1, itemsCount(data1) - 1) > 23 * ONE_HOUR_IN_SECONDS)
            removeBlock(data1, data2, itemsCount(data1) - 1)
    }
}
