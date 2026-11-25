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
    private volatile Object accountSource;
    private volatile boolean ready;
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
        for (XposedInterface.HookHandle h : handles) {
            try { h.unhook(); } catch (Throwable ignored) {}
        }
        handles.clear();
        accountSource = null;
        accountListMethod = null;
        context = null;
    }

    /**
     * Discover using two semantic string anchors. Validate all primary signatures
     * before installing and roll back a partial installation.
     */
    private synchronized void install(ClassLoader loader, Context ctx) {
        if (ready || detached || loader == null || discoveryAttempted) return;
        discoveryAttempted = true;
        String cached = GooglePlayDiscovery.cached(ctx);
        if (cached != null && installPrimary(loader, cached)) {
            module.log(Log.INFO, TAG, "Validated version cache: " + cached);
            finishInstall(loader, ctx, cached);
            return;
        }
        GooglePlayDiscovery.clear(ctx);
        String discovered = GooglePlayDiscovery.discover(module, loader, ctx);
        if (discovered != null && installPrimary(loader, discovered)) {
            finishInstall(loader, ctx, discovered);
            GooglePlayDiscovery.remember(ctx, discovered);
            return;
        }
        // Explicitly reviewed mapping for the supplied APK; never apply it
        // to a different version just because an obfuscated name exists.
        if (GooglePlayDiscovery.isReviewedVersion(ctx) && !"ppr".equals(discovered)
                && installPrimary(loader, "ppr")) {
            finishInstall(loader, ctx, "ppr");
            GooglePlayDiscovery.remember(ctx, "ppr");
            return;
        }
        if (!"poc".equals(discovered) && installPrimary(loader, "poc")) {
            finishInstall(loader, ctx, "poc");
            GooglePlayDiscovery.remember(ctx, "poc");
            return;
        }
        module.log(Log.WARN, TAG, "No validated account mapping; original behavior retained");
    }

    private boolean installPrimary(ClassLoader loader, String className) {
        int initial = handles.size();
        try {
            Class<?> source = Class.forName(className, false, loader);
            Method a = find(source, "a", String.class);
            Method b = find(source, "b");
            Method f = find(source, "f");
            Method i = find(source, "i");
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
                if (field.getType() == AccountManager.class) accountManagerField = true;
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
            ready = true;
            module.log(Log.INFO, TAG, "Validated primary account hooks: " + className);
            return true;
        } catch (Throwable failure) {
            while (handles.size() > initial) {
                XposedInterface.HookHandle h = handles.remove(handles.size() - 1);
                try { h.unhook(); } catch (Throwable suppressed) {}
            }
            accountListMethod = null;
            ready = false;
            diagnostic("Primary mapping " + className, failure);
            return false;
        }
    }

    private void finishInstall(ClassLoader loader, Context ctx, String mapping) {
        if ("poc".equals(mapping)) {
            installCheckout(loader);
        } else if ("ppr".equals(mapping) && GooglePlayDiscovery.isReviewedVersion(ctx)) {
            installReviewedCheckout(loader);
        } else {
            module.log(Log.INFO, TAG, "Primary mapping only; secondary checkout not validated for this version");
        }
    }

    /** Secondary model identified in the supplied build by its billing-account Bundle key. */
    private void installReviewedCheckout(ClassLoader loader) {
        try {
            Class<?> model = Class.forName("tar", false, loader);
            Field billing = field(model, "c");
            if (billing.getType() != String.class) throw new NoSuchFieldException("Checkout model");
            Constructor<?> ctor = model.getDeclaredConstructor(Context.class, Bundle.class);
            add(ctor, chain -> {
                Object result = chain.proceed();
                try {
                    Account choice = selected(null);
                    if (choice != null) billing.set(chain.getThisObject(), choice.name);
                } catch (Throwable ignored) {}
                return result;
            });
            module.log(Log.INFO, TAG, "Reviewed checkout model hook installed");
        } catch (Throwable failure) {
            diagnostic("Reviewed checkout model", failure);
        }
        // The saved-state route is not used to rewrite an in-progress purchase.
        // A new purchase flow must be opened after changing the selected account.
    }

    private void diagnostic(String stage, Throwable failure) {
        // Do not emit exception messages or stack traces which may contain account data.
        module.log(Log.WARN, TAG, stage + ": " + failure.getClass().getSimpleName());
    }

    private void installCheckout(ClassLoader loader) {
        try {
            Class<?> wfm = Class.forName("wfm", false, loader);
            Class<?> tbj = Class.forName("tbj", false, loader);
            Class<?> tct = Class.forName("tct", false, loader);
            Class<?> optional;
            try { optional = Class.forName("j$.util.Optional", false, loader); }
            catch (ClassNotFoundException e) { optional = java.util.Optional.class; }
            Method method = find(wfm, "b", tbj, optional, Context.class, boolean.class);
            Field account = field(tct, "a");
            if (account.getType() == Account.class && tct.isAssignableFrom(method.getReturnType())) {
                add(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        Account choice = selected(null);
                        if (choice != null && tct.isInstance(result)) account.set(result, choice);
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> szf = Class.forName("szf", false, loader);
            Field billing = field(szf, "c");
            if (billing.getType() == String.class) {
                Constructor<?> ctor = szf.getDeclaredConstructor(Context.class, Bundle.class);
                add(ctor, chain -> {
                    Object result = chain.proceed();
                    try {
                        Account choice = selected(null);
                        if (choice != null) billing.set(chain.getThisObject(), choice.name);
                    } catch (Throwable ignored) {}
                    return result;
                });
                try {
                    Class<?> sky = Class.forName("sky", false, loader);
                    Field owner = field(sky, "a");
                    if (szf.isAssignableFrom(owner.getType())) {
                        add(find(sky, "accept", Object.class), chain -> {
                            Object result = chain.proceed();
                            try {
                                Account choice = selected(null);
                                Object model = owner.get(chain.getThisObject());
                                if (choice != null && szf.isInstance(model)) billing.set(model, choice.name);
                            } catch (Throwable ignored) {}
                            return result;
                        });
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> sxk = Class.forName("sxk", false, loader);
            add(find(sxk, "onSaveInstanceState", Bundle.class), chain -> {
                Object result = chain.proceed();
                try {
                    Account choice = selected(null);
                    if (choice != null && chain.getArg(0) instanceof Bundle) {
                        ((Bundle) chain.getArg(0)).putString("BillingAccountModel.billingAccountName", choice.name);
                    }
                } catch (Throwable ignored) {}
                return result;
            });
        } catch (Throwable ignored) {}
    }

    private Account selected(Object source) {
        if (!ready || detached) return null;
        Account choice = publish(source);
        if (choice != null && overrideObserved.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "Selected account returned to a hooked route (identity omitted)");
        }
        return choice;
    }

    private Account publish(Object source) {
        Context c = context;
        if (c == null || detached) return null;
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
            try { data.putString("version", c.getPackageManager().getPackageInfo(PACKAGE, 0).versionName); }
            catch (Throwable ignored) {}
            Bundle reply = c.getContentResolver().call(BRIDGE, "sync", null, data);
            if (!ready || reply == null) return null;
            // Never synthesize an account or trust an email absent from this process.
            return accounts.get(reply.getString("selected"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method find(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
