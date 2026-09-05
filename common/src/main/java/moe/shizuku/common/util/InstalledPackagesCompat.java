package moe.shizuku.common.util;

import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.hidden.compat.util.SystemServiceBinder;

/**
 * Android 17 (API 37, Beta 3+) changed
 * {@code IPackageManager.getInstalledPackages(JI)} return type from
 * {@code android.content.pm.ParceledListSlice} to
 * {@code android.content.pm.PackageInfoList} (a subclass with the same
 * {@code getList()} accessor).
 *
 * <p>Code compiled against the old stub embeds the old descriptor
 * {@code getInstalledPackages(JI)Landroid/content/pm/ParceledListSlice;} so on
 * 37+ it throws {@link NoSuchMethodError} at the call site (or
 * {@link ClassCastException} inside hidden-compat). This helper tries the
 * fast path first, then falls back to pure reflection which ignores the
 * return type and extracts the list via {@code getList()}.
 *
 * <p>Works on API 24-36 (fast path), 37 / QPR1 / QPR2 (reflection fallback),
 * on both the server side (raw {@code package} binder) and the manager side
 * (Shizuku-hooked binder via {@code SystemServiceBinder}).
 */
public final class InstalledPackagesCompat {

    private InstalledPackagesCompat() {
    }

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) throws RemoteException {
        // Fast path: works on API < 37.
        try {
            android.content.pm.ParceledListSlice<PackageInfo> slice =
                    rikka.hidden.compat.PackageManagerApis.getInstalledPackages(flags, userId);
            if (slice != null) {
                List<PackageInfo> list = slice.getList();
                return list != null ? list : new ArrayList<>();
            }
            return new ArrayList<>();
        } catch (NoSuchMethodError | ClassCastException | NoClassDefFoundError e) {
            // Android 17+: fall through to reflection.
        } catch (RemoteException e) {
            throw e;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NoSuchMethodError
                    || cause instanceof ClassCastException
                    || cause instanceof NoClassDefFoundError) {
                // fall through
            } else {
                throw e;
            }
        }

        return getInstalledPackagesReflective(flags, userId);
    }

    public static List<PackageInfo> getInstalledPackagesNoThrow(long flags, int userId) {
        try {
            List<PackageInfo> result = getInstalledPackages(flags, userId);
            return result != null ? result : Collections.emptyList();
        } catch (Throwable tr) {
            android.util.Log.w("InstalledPackagesCompat", "getInstalledPackages failed", tr);
            return Collections.emptyList();
        }
    }

    // Overload accepting int flags (some old branches use int).
    public static List<PackageInfo> getInstalledPackagesNoThrow(int flags, int userId) {
        return getInstalledPackagesNoThrow((long) flags, userId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<PackageInfo> getInstalledPackagesReflective(long flags, int userId) throws RemoteException {
        try {
            IBinder binder = null;
            try {
                // NB: SystemServiceBinder has NO static get(String) — use an
                // instance. getBinder() applies the Shizuku hook on the manager
                // side (ShizukuBinderWrapper) and returns the raw binder on the
                // server side. asInterface descriptor is unchanged on 37+, only
                // getInstalledPackages return type changed, so this reference
                // is safe.
                binder = new SystemServiceBinder<>("package", IPackageManager.Stub::asInterface).getBinder();
            } catch (Throwable ignore) {
            }
            if (binder == null) {
                try {
                    binder = android.os.ServiceManager.getService("package");
                } catch (Throwable ignore) {
                }
            }
            if (binder == null) {
                return new ArrayList<>();
            }

            // IPackageManager$Stub.asInterface(binder) via reflection so we never
            // embed the old method descriptor in our own bytecode.
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object pm = asInterface.invoke(null, binder);
            if (pm == null) {
                return new ArrayList<>();
            }

            // Find getInstalledPackages(long, int) or (int, int).
            // getMethod() matches on name + params only, so it finds the new
            // PackageInfoList-returning method on 37+ as well.
            Method target = null;
            boolean takesLong = true;
            try {
                target = pm.getClass().getMethod("getInstalledPackages", long.class, int.class);
            } catch (NoSuchMethodException e) {
                target = pm.getClass().getMethod("getInstalledPackages", int.class, int.class);
                takesLong = false;
            }

            Object result;
            try {
                if (takesLong) {
                    result = target.invoke(pm, flags, userId);
                } else {
                    result = target.invoke(pm, (int) flags, userId);
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RemoteException) throw (RemoteException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RemoteException(cause != null ? cause.toString() : e.toString());
            }

            if (result == null) {
                return new ArrayList<>();
            }

            // Both ParceledListSlice and PackageInfoList expose getList().
            // Harden for QPR1/QPR2 renames: try getList, getPackageInfoList,
            // then direct mList field.
            if (result instanceof List) {
                return (List<PackageInfo>) result;
            }
            Object listObj = extractListReflective(result);
            if (listObj instanceof List) {
                return (List<PackageInfo>) listObj;
            }
            return new ArrayList<>();
        } catch (RemoteException e) {
            throw e;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RemoteException(cause != null ? cause.toString() : e.toString());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteException(e.toString());
        }
    }

    /**
     * Extract the list from ParceledListSlice / PackageInfoList without
     * embedding either return-type descriptor. Tries known accessors, then
     * the backing field.
     */
    private static Object extractListReflective(Object slice) throws Exception {
        String[] methods = {"getList", "getPackageInfoList", "getItems"};
        for (String name : methods) {
            try {
                Method m = slice.getClass().getMethod(name);
                return m.invoke(slice);
            } catch (NoSuchMethodException ignored) {
            }
        }
        // Last resort: ParceledListSlice stores items in mList.
        Class<?> c = slice.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("mList");
                f.setAccessible(true);
                return f.get(slice);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException("no getList accessor on " + slice.getClass());
    }
}
