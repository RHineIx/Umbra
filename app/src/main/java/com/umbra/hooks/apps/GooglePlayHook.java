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
                    install(c.getClassLoader());
                    publish(null);
                }
            } catch (Throwable ignored) { /* Never break Play Store startup. */ }
            return result;
        });
        install(loader);
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
        context = null;
    }

    /**
     * Known PSwitch symbol family. Validate all primary signatures before
     * installing; unfamiliar builds remain unmodified. No speculative matching.
     */
    private synchronized void install(ClassLoader loader) {
        if (ready || detached || loader == null) return;
        int initial = handles.size();
        try {
            Class<?> poc = Class.forName("poc", false, loader);
            Method a = find(poc, "a", String.class);
            Method b = find(poc, "b");
            Method f = find(poc, "f");
            if (a.getReturnType() != Account.class || b.getReturnType() != Account.class
                    || f.getReturnType() != String.class) throw new NoSuchMethodException("Primary signatures");
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
            // Secondary checkout routes from the upstream implementation.
            // If a known route no longer matches, it is skipped rather than guessed.
            installCheckout(loader);
            ready = true;
            module.log(Log.INFO, TAG, "Known Play Store account hooks installed");
        } catch (Throwable ignored) {
            while (handles.size() > initial) {
                XposedInterface.HookHandle h = handles.remove(handles.size() - 1);
                try { h.unhook(); } catch (Throwable suppressed) {}
            }
            ready = false;
            module.log(Log.WARN, TAG, "Play Store symbols unsupported; original behavior retained");
        }
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
        return publish(source);
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
                    Object list = find(instance.getClass(), "i").invoke(instance);
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
