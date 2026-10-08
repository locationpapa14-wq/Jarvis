package com.jarvis.assistant.device

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer

data class ContactMatch(val name: String, val number: String)

sealed class ContactLookup {
    data class Unique(val match: ContactMatch) : ContactLookup()
    data class Multiple(val matches: List<ContactMatch>) : ContactLookup()
    object None : ContactLookup()
}

class ContactResolver(private val ctx: Context) {

    private fun norm(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()

    private fun distance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return dp[a.length][b.length]
    }

    fun resolve(spoken: String): ContactLookup {
        val q = norm(spoken)
        if (q.isEmpty()) return ContactLookup.None
        val all = mutableListOf<ContactMatch>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                all += ContactMatch(n, num)
            }
        }
        val exact = all.filter { norm(it.name) == q }.distinctBy { norm(it.name) + it.number.filter(Char::isDigit) }
        if (exact.isNotEmpty()) return pick(exact)
        val partial = all.filter { norm(it.name).contains(q) || q.contains(norm(it.name)) }
            .distinctBy { it.name }
        if (partial.isNotEmpty()) return pick(partial)
        val fuzzy = all.filter { c ->
            norm(c.name).split(" ").any { w -> w.isNotEmpty() && distance(w, q) <= if (q.length > 4) 2 else 1 }
        }.distinctBy { it.name }
        return if (fuzzy.isEmpty()) ContactLookup.None else pick(fuzzy)
    }

    private fun pick(list: List<ContactMatch>): ContactLookup {
        val names = list.map { norm(it.name) }.distinct()
        return if (names.size == 1) ContactLookup.Unique(list.first()) else ContactLookup.Multiple(list.distinctBy { it.name })
    }
}
