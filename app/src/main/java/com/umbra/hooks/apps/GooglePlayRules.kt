package com.umbra.hooks.apps

/** Pure matching/serialization rules shared by discovery and JVM regression tests. */
internal object GooglePlayRules {
    const val SCHEMA = 2
    const val CURRENT = "is not valid, selecting first available account:"
    const val CACHE_A = "Lazy initialization of accounts cache"
    const val CACHE_B = "Accounts cache should not be null after initialization"
    const val BILLING = "BillingAccountModel.billingAccountName"
    const val ACCOUNT = "android.accounts.Account"
    const val STRING = "java.lang.String"
    const val LIST = "java.util.List"
    const val MANAGER = "android.accounts.AccountManager"

    data class Primary(val owner: String, val lookup: String, val current: String,
                       val name: String, val list: String)
    data class Checkout(val owner: String, val field: String)
    data class Mapping(val primary: Primary, val checkout: Checkout?)
    data class MethodEvidence(
        val owner: String, val name: String, val descriptor: String,
        val params: List<String>, val returns: String,
        val strings: Set<String> = emptySet(), val calls: Set<String> = emptySet()
    )
    data class OwnerEvidence(val owner: String, val managerField: Boolean,
                             val methods: List<MethodEvidence>)
    data class CheckoutEvidence(val owner: String, val descriptor: String,
                                val strings: Set<String>, val calls: Set<String>,
                                val writtenStringFields: List<String>)

    fun primary(owners: List<OwnerEvidence>): Primary? {
        val matched = owners.mapNotNull { owner ->
            if (!owner.managerField) return@mapNotNull null
            val methods = owner.methods.filter { it.owner == owner.owner }
            val current = methods.filter { it.params.isEmpty() && it.returns == ACCOUNT &&
                it.strings.any { s -> CURRENT in s } }.singleOrNull() ?: return@mapNotNull null
            val list = methods.filter { it.params.isEmpty() && it.returns == LIST &&
                CACHE_A in it.strings }.singleOrNull() ?: return@mapNotNull null
            // The cache invariant may disappear on a new build. The AccountManager
            // call and the independent lookup/list/name edges are still required.
            if (list.calls.none { it.startsWith("Landroid/accounts/AccountManager;->getAccounts") })
                return@mapNotNull null
            val lookup = methods.filter { it.params == listOf(STRING) && it.returns == ACCOUNT }
                .singleOrNull() ?: return@mapNotNull null
            val name = methods.filter { it.params.isEmpty() && it.returns == STRING &&
                current.descriptor in it.calls }.singleOrNull() ?: return@mapNotNull null
            if (lookup.descriptor !in current.calls || list.descriptor !in lookup.calls)
                return@mapNotNull null
            Primary(owner.owner, lookup.name, current.name, name.name, list.name)
        }
        return matched.singleOrNull()?.takeIf(::validPrimary)
    }

    fun checkout(candidates: List<CheckoutEvidence>): Checkout? {
        // Never select the first field or first constructor from ambiguous data.
        val hit = candidates.filter { BILLING in it.strings &&
            "Landroid/os/Bundle;->getString(Ljava/lang/String;)Ljava/lang/String;" in it.calls }
            .singleOrNull() ?: return null
        val field = hit.writtenStringFields.distinct().singleOrNull() ?: return null
        return Checkout(hit.owner, field).takeIf(::validCheckout)
    }

    private fun member(s: String) = s.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))
    private fun owner(s: String) = s.split('.').all(::member)
    private fun validPrimary(p: Primary) = owner(p.owner) &&
        listOf(p.lookup,p.current,p.name,p.list).all(::member) &&
        listOf(p.lookup,p.current,p.name,p.list).toSet().size == 4
    private fun validCheckout(c: Checkout) = owner(c.owner) && member(c.field)

    fun encode(m: Mapping): String = with(m.primary) {
        listOf(owner,lookup,current,name,list,m.checkout?.owner.orEmpty(),m.checkout?.field.orEmpty()).joinToString("|")
    }
    fun decode(value: String?): Mapping? {
        if (value == null || value.length > 2048) return null
        val s=value.split('|')
        if (s.size != 7) return null
        val p=Primary(s[0],s[1],s[2],s[3],s[4])
        if (!validPrimary(p)) return null
        val c=if (s[5].isEmpty() && s[6].isEmpty()) null else Checkout(s[5],s[6])
        if (c != null && !validCheckout(c)) return null
        return Mapping(p,c)
    }

    fun reviewed(versionCode: Long, versionName: String?, baseSha256: String): Mapping? {
        if (versionCode != 85441140L || versionName != "54.4.11-34 [0] [PR] 1025789771" ||
            baseSha256 != "77c13c4f0e0de1fdbaf1afea11cdd2dde8f913255137b92d45738a43040ba1f7") return null
        return Mapping(Primary("ppr","a","b","f","i"),Checkout("tar","c"))
    }
}
