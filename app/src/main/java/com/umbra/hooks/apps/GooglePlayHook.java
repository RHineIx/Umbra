package com.umbra.hooks.apps;

/*
 * Account routing adapted from PSwitch (Copyright 2026 PSwitch contributors).
 * MIT license; see third-party/PSwitch-LICENSE.txt.
 */
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class GooglePlayHook {
    public static final String PACKAGE = "com.android.vending";
    private static final String TAG = "UMBRA-PLAY";
    private static final Uri BRIDGE = Uri.parse("content://com.umbra.hooks.billing");
    private final XposedModule module;
    private final List<XposedInterface.HookHandle> handles = new CopyOnWriteArrayList<>();
    private volatile Context context;
    private volatile String storeVersion;
    private volatile Object accountSource;
    private volatile boolean ready;
    private volatile boolean checkoutReady;
    private final ThreadLocal<Boolean> publishing = new ThreadLocal<>();
    private volatile boolean detached;
    private volatile Method accountListMethod;
    private boolean discoveryAttempted;
    private final java.util.concurrent.atomic.AtomicBoolean overrideObserved = new java.util.concurrent.atomic.AtomicBoolean();

    public GooglePlayHook(XposedModule module) { this.module = module; }

    public void start(ClassLoader loader) throws Throwable {
        // The final package loader may be available before Application.attach.
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        add(attach, chain -> {
            Object result = chain.proceed();
            try {
                Context c = (Context) chain.getArg(0);
                if (PACKAGE.equals(c.getPackageName()) && !detached) {
                    context = c.getApplicationContext() != null ? c.getApplicationContext() : c;
                    install(c.getClassLoader(), c);
                    publish(null);
                }
            } catch (Throwable failure) { diagnostic("Application initialization", failure); }
            return result;
        });
        // Wait for a real context before scanning, reading the version or publishing status.
    }

    private void add(Executable method, XposedInterface.Hooker hooker) {
        handles.add(module.hook(method).intercept(hooker));
    }

    public synchronized void detachAll() {
        detached = true;
        ready = false;
        checkoutReady = false;
        for (XposedInterface.HookHandle h : handles) {
            try { h.unhook(); } catch (Throwable ignored) {}
        }
        handles.clear();
        accountSource = null;
        accountListMethod = null;
        context = null;
        storeVersion = null;
    }

    /**
     * Discover using two semantic string anchors. Validate all primary signatures
     * before installing and roll back a partial installation.
     */
    private synchronized void install(ClassLoader loader, Context ctx) {
        if (ready || detached || loader == null || discoveryAttempted) return;
        discoveryAttempted = true;
        GooglePlayDiscovery.Identity id = GooglePlayDiscovery.identity(ctx);
        // Package identity is fixed for this process. Only version metadata is
        // cached; selected account and account membership are checked on every call.
        storeVersion = id == null ? null : id.getName();
        if (storeVersion == null) {
            try { storeVersion = ctx.getPackageManager().getPackageInfo(PACKAGE, 0).versionName; }
            catch (Throwable ignored) {}
        }
        GooglePlayRules.Mapping reviewed = GooglePlayDiscovery.reviewed(id);
        if (reviewed != null && installMapping(loader, reviewed)) {
            module.log(Log.INFO, TAG, "Exact reviewed build mapping installed");
            return;
        }
        GooglePlayRules.Mapping cached = GooglePlayDiscovery.cached(ctx, id);
        if (cached != null && installMapping(loader, cached)) {
            module.log(Log.INFO, TAG, "Validated exact-build cached mapping installed");
            return;
        }
        GooglePlayDiscovery.clear(ctx);
        GooglePlayRules.Mapping discovered = GooglePlayDiscovery.discover(module, loader, ctx);
        if (discovered != null && installMapping(loader, discovered)) {
            // Cache only complete, successfully installed mappings.
            GooglePlayDiscovery.remember(ctx, id, discovered);
            return;
        }
        module.log(Log.WARN, TAG, "No complete validated mapping; original behavior retained");
    }

    private boolean installMapping(ClassLoader loader, GooglePlayRules.Mapping mapping) {
        // Fail closed for incomplete checkout paths. Account selection must not
        // silently claim success when the checkout route is missing.
        if (mapping.getCheckout() == null) return false;
        GooglePlayRules.Primary p = mapping.getPrimary();
        GooglePlayRules.Checkout checkout = mapping.getCheckout();
        int initial = handles.size();
        try {
            Class<?> source = Class.forName(p.getOwner(), false, loader);
            Method a = source.getDeclaredMethod(p.getLookup(), String.class);
            Method b = source.getDeclaredMethod(p.getCurrent());
            Method f = source.getDeclaredMethod(p.getName());
            Method i = source.getDeclaredMethod(p.getList());
            for (Method m : new Method[]{a,b,f,i}) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) ||
                        java.lang.reflect.Modifier.isNative(m.getModifiers()))
                    throw new NoSuchMethodException("Non-concrete primary method");
                m.setAccessible(true);
            }
            if (a.getReturnType() != Account.class || b.getReturnType() != Account.class
                    || f.getReturnType() != String.class
                    || !List.class.isAssignableFrom(i.getReturnType())
                    || java.lang.reflect.Modifier.isStatic(a.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(b.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(i.getModifiers())) {
                throw new NoSuchMethodException("Primary signatures");
            }
            boolean accountManagerField = false;
            for (Field field : source.getDeclaredFields()) {
                if (field.getType() == AccountManager.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) accountManagerField = true;
            }
            if (!accountManagerField) throw new NoSuchFieldException("AccountManager");
            accountListMethod = i;
            add(a, chain -> {
                Account selected = selected(chain.getThisObject());
                return selected == null ? chain.proceed() : selected;
            });
            add(b, chain -> {
                Account selected = selected(chain.getThisObject());
                return selected == null ? chain.proceed() : selected;
            });
            add(f, chain -> {
                Account selected = selected(chain.getThisObject());
                return selected == null ? chain.proceed() : selected.name;
            });
            installCheckout(loader, checkout);
            checkoutReady = true;
            ready = true;
            module.log(Log.INFO, TAG, "Validated primary and checkout hooks installed");
            return true;
        } catch (Throwable failure) {
            while (handles.size() > initial) {
                XposedInterface.HookHandle h = handles.remove(handles.size() - 1);
                try { h.unhook(); } catch (Throwable suppressed) {}
            }
            accountListMethod = null;
            ready = false;
            checkoutReady = false;
            diagnostic("Mapping installation", failure);
            return false;
        }
    }

    private void installCheckout(ClassLoader loader, GooglePlayRules.Checkout mapping) throws Throwable {
        Class<?> model = Class.forName(mapping.getOwner(), false, loader);
        Field billing = model.getDeclaredField(mapping.getField());
        if (billing.getType() != String.class || java.lang.reflect.Modifier.isStatic(billing.getModifiers()))
            throw new NoSuchFieldException("Checkout model");
        billing.setAccessible(true);
        Constructor<?> ctor = model.getDeclaredConstructor(Context.class, Bundle.class);
        add(ctor, chain -> {
            Object result = chain.proceed();
            try {
                Account choice = selected(null);
                if (choice != null) billing.set(chain.getThisObject(), choice.name);
            } catch (Throwable ignored) {}
            return result;
        });
    }

    private void diagnostic(String stage, Throwable failure) {
        // Do not emit exception messages or stack traces which may contain account data.
        module.log(Log.WARN, TAG, stage + ": " + failure.getClass().getSimpleName());
    }

    private Account selected(Object source) {
        if (!ready || !checkoutReady || detached || Boolean.TRUE.equals(publishing.get())) return null;
        Account choice = publish(source);
        if (choice != null && overrideObserved.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "Selected account returned to a hooked route (identity omitted)");
        }
        return choice;
    }

    private Account publish(Object source) {
        Context c = context;
        if (c == null || detached || Boolean.TRUE.equals(publishing.get())) return null;
        publishing.set(true);
        try {
            if (source != null) accountSource = source;
            Map<String, Account> accounts = new LinkedHashMap<>();
            Object instance = accountSource;
            if (instance != null) {
                try {
                    Method listMethod = accountListMethod;
                    Object list = listMethod == null ? null : listMethod.invoke(instance);
                    if (list instanceof List<?>) {
                        for (Object item : (List<?>) list) if (item instanceof Account) {
                            Account account = (Account) item;
                            if ("com.google".equals(account.type)) accounts.put(account.name, account);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            try {
                for (Account account : AccountManager.get(c).getAccountsByType("com.google")) {
                    accounts.put(account.name, account);
                }
            } catch (Throwable ignored) {}
            Bundle data = new Bundle();
            data.putStringArrayList("accounts", new ArrayList<>(accounts.keySet()));
            data.putString("status", ready ? "ready" : "unsupported");
            String version = storeVersion;
            if (version != null) data.putString("version", version);
            Bundle reply = c.getContentResolver().call(BRIDGE, "sync", null, data);
            if (!ready || reply == null) return null;
            // Never synthesize an account or trust an email absent from this process.
            return accounts.get(reply.getString("selected"));
        } catch (Throwable ignored) {
            return null;
        } finally {
            publishing.remove();
        }
    }

}
