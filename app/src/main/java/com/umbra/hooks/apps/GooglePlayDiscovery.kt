package com.umbra.hooks.apps

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.FieldUsingType
import java.io.File
import java.lang.reflect.Modifier
import com.umbra.hooks.core.ApkDigest

/** Offline semantic discovery with exact installed APK identity and versioned cache. */
internal object GooglePlayDiscovery {
    private const val TAG = "UMBRA-PLAY"
    private const val STORE = "com.android.vending"
    private const val CACHE = "umbra_play_mapping"

    data class Identity(val code: Long, val name: String?, val baseHash: String, val key: String)

    private fun sha256(path: String): String = ApkDigest.sha256(File(path))

    @JvmStatic fun identity(context: Context): Identity? = runCatching {
        val info=context.packageManager.getPackageInfo(STORE,0)
        @Suppress("DEPRECATION")
        val code=if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val base=sha256(context.applicationInfo.sourceDir)
        val splits=context.applicationInfo.splitSourceDirs.orEmpty().sorted().map { sha256(it) }.sorted()
        val key=listOf(GooglePlayRules.SCHEMA.toString(),code.toString(),info.versionName.orEmpty(),
            info.lastUpdateTime.toString(),base,splits.joinToString(",")).joinToString(":")
        Identity(code,info.versionName,base,key)
    }.getOrNull()

    @JvmStatic fun reviewed(id: Identity?): GooglePlayRules.Mapping? =
        id?.let { GooglePlayRules.reviewed(it.code,it.name,it.baseHash) }

    @JvmStatic fun cached(context: Context, id: Identity?): GooglePlayRules.Mapping? {
        if (id == null) return null
        val prefs=context.getSharedPreferences(CACHE,Context.MODE_PRIVATE)
        if (prefs.getString("version",null) != id.key) return null
        return GooglePlayRules.decode(prefs.getString("mapping",null))
    }

    @JvmStatic fun clear(context: Context) {
        context.getSharedPreferences(CACHE,Context.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic fun remember(context: Context, id: Identity?, mapping: GooglePlayRules.Mapping) {
        if (id == null) return
        context.getSharedPreferences(CACHE,Context.MODE_PRIVATE).edit()
            .putString("version",id.key).putString("mapping",GooglePlayRules.encode(mapping)).apply()
    }

    @JvmStatic fun discover(module: XposedModule, loader: ClassLoader, context: Context): GooglePlayRules.Mapping? {
        return try {
            try {
                System.loadLibrary("dexkit")
            } catch (_: UnsatisfiedLinkError) {
                System.load(File(module.moduleApplicationInfo.nativeLibraryDir,"libdexkit.so").absolutePath)
            }
            // When split APKs exist, search the full loader. Never replace an
            // ambiguous loader result with a narrower base-only result.
            val result=if (context.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                DexKitBridge.create(context.applicationInfo.sourceDir).use { scan(it) }
            } else {
                DexKitBridge.create(loader,true).use { scan(it) }
            }
            module.log(Log.INFO,TAG,if (result == null) "DexKit: no unique semantic primary mapping"
                else "DexKit: unique primary; checkout candidate=" + (result.checkout != null))
            result
        } catch (failure: Throwable) {
            module.log(Log.WARN,TAG,"DexKit discovery failed: " + failure.javaClass.simpleName)
            null
        }
    }

    private fun instance(m: MethodData) = m.isMethod && !Modifier.isStatic(m.modifiers) &&
        !Modifier.isAbstract(m.modifiers) && !Modifier.isNative(m.modifiers)

    private fun scan(bridge: DexKitBridge): GooglePlayRules.Mapping? {
        val current=bridge.findMethod {
            matcher { usingStrings(GooglePlayRules.CURRENT); returnType(GooglePlayRules.ACCOUNT) }
        }.filter { instance(it) && it.paramTypeNames.isEmpty() }
        val list=bridge.findMethod {
            matcher { usingStrings(GooglePlayRules.CACHE_A); returnType(GooglePlayRules.LIST) }
        }.filter { instance(it) && it.paramTypeNames.isEmpty() }
        val owners=current.map { it.className }.toSet().intersect(list.map { it.className }.toSet())
        val evidence=owners.mapNotNull { owner ->
            val cls=current.first { it.className == owner }.declaredClass ?: return@mapNotNull null
            val methods=cls.methods.filter { m ->
                instance(m) && ((m.paramTypeNames.isEmpty() && m.returnTypeName in
                    listOf(GooglePlayRules.ACCOUNT,GooglePlayRules.LIST,GooglePlayRules.STRING)) ||
                    (m.paramTypeNames == listOf(GooglePlayRules.STRING) && m.returnTypeName == GooglePlayRules.ACCOUNT))
            }.map { m -> GooglePlayRules.MethodEvidence(m.className,m.name,m.descriptor,m.paramTypeNames,
                m.returnTypeName,m.usingStrings.toSet(),m.invokes.map { it.descriptor }.toSet()) }
            GooglePlayRules.OwnerEvidence(owner,cls.fields.any {
                it.typeName == GooglePlayRules.MANAGER && !Modifier.isStatic(it.modifiers)
            },methods)
        }
        val primary=GooglePlayRules.primary(evidence) ?: return null
        val constructors=bridge.findMethod {
            matcher { usingStrings(GooglePlayRules.BILLING); returnType("void") }
        }.filter { it.isConstructor && it.paramTypeNames == listOf("android.content.Context","android.os.Bundle") }
            .map { m ->
                GooglePlayRules.CheckoutEvidence(m.className,m.descriptor,m.usingStrings.toSet(),
                    m.invokes.map { it.descriptor }.toSet(),m.usingFields.filter {
                        it.usingType == FieldUsingType.Write && it.field.className == m.className &&
                            it.field.typeName == GooglePlayRules.STRING && !Modifier.isStatic(it.field.modifiers)
                    }.map { it.field.name })
            }
        return GooglePlayRules.Mapping(primary,GooglePlayRules.checkout(constructors))
    }
}
