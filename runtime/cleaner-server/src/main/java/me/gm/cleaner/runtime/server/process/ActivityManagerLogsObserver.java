package me.gm.cleaner.runtime.server.process;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.runtime.server.BuildConfig;
import me.gm.cleaner.runtime.server.CleanerServer;
import me.gm.cleaner.runtime.server.ServerConstants;
import me.gm.cleaner.runtime.server.util.StringUtils;

public class ActivityManagerLogsObserver extends BaseProcessObserver {

    public static final class ParsedProcLine {
        public final int pid;
        public final String processName;
        public final String principal;

        ParsedProcLine(int pid, String processName, String principal) {
            this.pid = pid;
            this.processName = processName;
            this.principal = principal;
        }
    }

    /**
     * Start-proc 行纯解析。threadtime 的 pid/tid 列非定宽，
     * 禁止按 tag 下标过滤——历史教训：INDEX_OF_TAG 按发射线程位宽随机丢行。
     * 无 brace 的 FastRestart 行同样接受（常被杀应用复活走此格式，
     * 以 ` for ` 为界；进程名/uid 段不可能含空格）。
     * 校验顺序与原内联逻辑一致（分隔符先验再切分），仅把无副作用的
     * 格式校验提前，避免为异形行触发 PackageInfoMapper 全量初始化。
     */
    static ParsedProcLine parseStartProcLine(String line, String amStartProc) {
        final var indexOfStartProc = line.indexOf(amStartProc);
        if (indexOfStartProc == -1) {
            return null;
        }
        // $pid:$processName/$logFormatAppPrincipalName for pre-top-activity, content provider, service {$packageName/$className} caller=$packageName
        // FastRestart 复活行无 brace：.../u0a273 for FastRestart com.tencent.mm caller=null
        // 有 brace 用 brace（与原逻辑完全一致）；无 brace 才退到 ` for ` 边界。
        final var indexOfBrace = line.indexOf('{');
        final int end;
        if (indexOfBrace != -1) {
            end = indexOfBrace;
        } else {
            final var forBoundary = line.indexOf(" for ", indexOfStartProc);
            if (forBoundary == -1) {
                return null;
            }
            end = forBoundary;
        }
        final var start = StringUtils.substring(line, indexOfStartProc + 28, end);
        // 异形行（如厂商定制 kill-reason 行）可能缺分隔符，先验下标再切分。
        final var startSlash = start.indexOf('/');
        final var startSpace = start.indexOf(' ');
        // FastRestart 无 brace 行：principal 直达行尾，没有尾随空格。
        final var principalEnd = startSpace == -1 ? start.length() : startSpace;
        if (startSlash == -1 || principalEnd <= startSlash) {
            return null;
        }
        final var startColon = start.indexOf(':');
        if (startColon == -1 || startColon > startSlash) {
            return null;
        }
        final var pidStr = StringUtils.substring(start, 0, startColon);
        if (!isDigitsOnly(pidStr)) {
            return null;
        }
        return new ParsedProcLine(
                Integer.parseInt(pidStr),
                StringUtils.substring(start, startColon + 1, startSlash),
                StringUtils.substring(start, startSlash + 1, principalEnd));
    }

    /**
     * Killing 行纯解析，同上禁止下标过滤。
     * $pid:$processName/$logFormatAppPrincipalName (adj 0): stop $packageName due to from pid $pid
     */
    static ParsedProcLine parseKillingLine(String line, String amKilling, String phantomProcessRecord) {
        final var indexOfKilling = line.indexOf(amKilling);
        if (indexOfKilling == -1 || line.length() < indexOfKilling + 25) {
            return null;
        }
        final var killing = StringUtils.substring(line, indexOfKilling + 25);
        if (killing.startsWith(phantomProcessRecord)) {
            return null;
        }
        // 同 start 分支：异形行缺分隔符时跳过，避免 substring 抛异常。
        final var killingSlash = killing.indexOf('/');
        final var killingSpace = killing.indexOf(' ');
        if (killingSlash == -1 || killingSpace == -1 || killingSpace <= killingSlash) {
            return null;
        }
        final var killingColon = killing.indexOf(':');
        if (killingColon == -1 || killingColon > killingSlash) {
            return null;
        }
        final var pidStr = StringUtils.substring(killing, 0, killingColon);
        if (!isDigitsOnly(pidStr)) {
            return null;
        }
        return new ParsedProcLine(
                Integer.parseInt(pidStr),
                StringUtils.substring(killing, killingColon + 1, killingSlash),
                StringUtils.substring(killing, killingSlash + 1, killingSpace));
    }

    private final CleanerServer mServer;
    private volatile boolean mHasAmStart = false;
    private volatile long mStartAtMs = 0L;
    private volatile long mLastReadAtMs = 0L;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /**
     * 当前 AM 日志采集子进程。
     *
     * <p>生命周期契约（本项目对 logcat 子进程的统一约定）：
     * 子进程必须由创建者持有句柄，并在**调用线程内同步终结**。
     * 原因：服务退出的路径是 {@code onDestroy()} → {@code System.exit(0)}，
     * JVM 会立即终止；把终结动作委托给另一个线程的 finally 块，
     * 必然来不及执行（实测：优雅退出后该 logcat 变为 PPID=1 的孤儿进程）。
     */
    private volatile Process mLogcatProcess;

    /** 停止流程是否已开始：用于终结"停止期间才创建出来"的子进程。 */
    private volatile boolean mStopped = false;

    /**
     * 主动停止标记：{@link #onDestroy()} 被调用时置位。
     *
     * <p>用于区分观察器线程的两种终止：
     * - 主动停止（用户停止服务 / 服务退出）：广播只是退出副产品，app 侧不应提示；
     * - 意外死亡（logcat 读取异常）：需要通知 app 侧走看门狗与提示闭环。
     */
    private volatile boolean mStopping = false;

    public ActivityManagerLogsObserver(final CleanerServer server) {
        mServer = server;
    }

    @Override
    public void onStart() {
        super.onStart();
        mStartAtMs = android.os.SystemClock.elapsedRealtime();
        mLastReadAtMs = 0L;
        // /system/bin/logcat -c
        final var clearLogcat = new char[0x15];

        clearLogcat[0x0] = '-';
        clearLogcat[0x1] = 'p';
        clearLogcat[0x2] = '}';
        clearLogcat[0x3] = 'v';
        clearLogcat[0x4] = 'r';
        clearLogcat[0x5] = 'b';
        clearLogcat[0x6] = 'e';
        clearLogcat[0x7] = '&';
        clearLogcat[0x8] = 'h';
        clearLogcat[0x9] = 'b';
        clearLogcat[0xa] = 'b';
        clearLogcat[0xb] = '\"';
        clearLogcat[0xc] = 'b';
        clearLogcat[0xd] = '`';
        clearLogcat[0xe] = 'w';
        clearLogcat[0xf] = 'r';
        clearLogcat[0x10] = 's';
        clearLogcat[0x11] = 't';
        clearLogcat[0x12] = '!';
        clearLogcat[0x13] = '/';
        clearLogcat[0x14] = '`';

        for (int i = 0; i < 0x15; ++i) {
            clearLogcat[i] ^= (i + 0x15) % 19;
        }
        // @see https://stackoverflow.com/questions/13931729/filtering-logcat-logs-on-commandline
        // /system/bin/logcat -s ActivityManager:V -v threadtime
        final var logcat = new char[0x35];

        logcat[0x0] = '%';
        logcat[0x1] = 'x';
        logcat[0x2] = 'u';
        logcat[0x3] = '~';
        logcat[0x4] = 'z';
        logcat[0x5] = 'j';
        logcat[0x6] = '}';
        logcat[0x7] = '>';
        logcat[0x8] = 'p';
        logcat[0x9] = 'z';
        logcat[0xa] = 'z';
        logcat[0xb] = ':';
        logcat[0xc] = 'z';
        logcat[0xd] = 'x';
        logcat[0xe] = '\u007F';
        logcat[0xf] = 'z';
        logcat[0x10] = '{';
        logcat[0x11] = 'o';
        logcat[0x12] = '<';
        logcat[0x13] = '0';
        logcat[0x14] = 'm';
        logcat[0x15] = '?';
        logcat[0x16] = 'a';
        logcat[0x17] = 'B';
        logcat[0x18] = 'V';
        logcat[0x19] = 'J';
        logcat[0x1a] = 'R';
        logcat[0x1b] = 'L';
        logcat[0x1c] = 'R';
        logcat[0x1d] = '^';
        logcat[0x1e] = 'e';
        logcat[0x1f] = 'H';
        logcat[0x20] = 'D';
        logcat[0x21] = 'a';
        logcat[0x22] = 'f';
        logcat[0x23] = 'g';
        logcat[0x24] = 'q';
        logcat[0x25] = '>';
        logcat[0x26] = 'S';
        logcat[0x27] = '&';
        logcat[0x28] = '*';
        logcat[0x29] = '~';
        logcat[0x2a] = ')';
        logcat[0x2b] = '~';
        logcat[0x2c] = 'c';
        logcat[0x2d] = '~';
        logcat[0x2e] = 'h';
        logcat[0x2f] = 'o';
        logcat[0x30] = 'k';
        logcat[0x31] = 'd';
        logcat[0x32] = 'x';
        logcat[0x33] = '\u007F';
        logcat[0x34] = 'v';

        for (int i = 0; i < 0x35; ++i) {
            logcat[i] ^= (i + 0x35) % 43;
        }
        // ActivityManager: Start proc
        final var amStartProc = new char[0x1b];

        amStartProc[0x0] = 'A';
        amStartProc[0x1] = 'b';
        amStartProc[0x2] = 'v';
        amStartProc[0x3] = 'j';
        amStartProc[0x4] = 'r';
        amStartProc[0x5] = 'l';
        amStartProc[0x6] = 'r';
        amStartProc[0x7] = '~';
        amStartProc[0x8] = 'E';
        amStartProc[0x9] = 'h';
        amStartProc[0xa] = 'd';
        amStartProc[0xb] = 'j';
        amStartProc[0xc] = 'k';
        amStartProc[0xd] = 'h';
        amStartProc[0xe] = '|';
        amStartProc[0xf] = '5';
        amStartProc[0x10] = '0';
        amStartProc[0x11] = 'B';
        amStartProc[0x12] = 'f';
        amStartProc[0x13] = 'r';
        amStartProc[0x14] = 'f';
        amStartProc[0x15] = 'a';
        amStartProc[0x16] = '6';
        amStartProc[0x17] = 'g';
        amStartProc[0x18] = 'j';
        amStartProc[0x19] = 'v';
        amStartProc[0x1a] = 'y';

        for (int i = 0; i < 0x1b; ++i) {
            amStartProc[i] ^= (i + 0x1b) % 27;
        }
        // ActivityManager: Killing
        final var amKilling = new char[0x18];

        amKilling[0x0] = 'B';
        amKilling[0x1] = 'g';
        amKilling[0x2] = 'q';
        amKilling[0x3] = 'o';
        amKilling[0x4] = 'q';
        amKilling[0x5] = 'a';
        amKilling[0x6] = '}';
        amKilling[0x7] = 's';
        amKilling[0x8] = 'F';
        amKilling[0x9] = 'm';
        amKilling[0xa] = 'c';
        amKilling[0xb] = 'o';
        amKilling[0xc] = 'h';
        amKilling[0xd] = 'u';
        amKilling[0xe] = 'c';
        amKilling[0xf] = '(';
        amKilling[0x10] = '3';
        amKilling[0x11] = '_';
        amKilling[0x12] = 'i';
        amKilling[0x13] = 'm';
        amKilling[0x14] = 'n';
        amKilling[0x15] = 'j';
        amKilling[0x16] = 'j';
        amKilling[0x17] = 'b';

        for (int i = 0; i < 0x18; ++i) {
            amKilling[i] ^= (i + 0x18) % 21;
        }
        // PhantomProcessRecord
        final var phantomProcessRecord = new char[0x14];

        phantomProcessRecord[0x0] = 'Q';
        phantomProcessRecord[0x1] = 'j';
        phantomProcessRecord[0x2] = 'b';
        phantomProcessRecord[0x3] = 'j';
        phantomProcessRecord[0x4] = 'q';
        phantomProcessRecord[0x5] = 'i';
        phantomProcessRecord[0x6] = 'j';
        phantomProcessRecord[0x7] = 'X';
        phantomProcessRecord[0x8] = '{';
        phantomProcessRecord[0x9] = 'e';
        phantomProcessRecord[0xa] = 'h';
        phantomProcessRecord[0xb] = 'i';
        phantomProcessRecord[0xc] = '~';
        phantomProcessRecord[0xd] = '}';
        phantomProcessRecord[0xe] = ']';
        phantomProcessRecord[0xf] = 'u';
        phantomProcessRecord[0x10] = 'r';
        phantomProcessRecord[0x11] = '}';
        phantomProcessRecord[0x12] = 'r';
        phantomProcessRecord[0x13] = 'e';

        for (int i = 0; i < 0x14; ++i) {
            phantomProcessRecord[i] ^= (i + 0x14) % 19;
        }
        mExecutor.execute(() -> {
            Process process = null;
            try {
                if (BuildConfig.DEBUG) {
                    try {
                        Runtime.getRuntime().exec(new String(clearLogcat));
                    } catch (final Throwable e) {
                        Log.e("AMLogs", "clear logcat failed", e);
                    }
                }

                while (true) {
                    process = Runtime.getRuntime().exec(new String(logcat));
                    registerLogcatProcess(process);
                    final var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                        // 存活心跳：只要还在消费行，尾巴就活着；卡住（Binder 阻塞/logd wedged）
                        // 时 readLine 不返回，心跳停滞，看门狗据此判假活（区别于 executor 关闭的真死）。
                        mLastReadAtMs = android.os.SystemClock.elapsedRealtime();
                        try {
                            final var parsedStart = parseStartProcLine(line, new String(amStartProc));
                            if (parsedStart != null) {
                                if (!mHasAmStart) {
                                    mHasAmStart = true;
                                }
                                final var uid = PackageInfoMapper.getUid(parsedStart.principal);
                                // 与 BaseProcessObserver 候选资格一致：隔离进程不进挂载链。
                                if (RuntimeFileUtils.INSTANCE.isIsolatedUid(uid)) {
                                    continue;
                                }
                                if (!isMounterActiveForUid(uid)) {
                                    continue;
                                }
                                final String packageName;
                                if (getMounter().mountForAllPackages()) {
                                    packageName = PackageInfoMapper.getPackageName(uid, parsedStart.processName);
                                } else {
                                    packageName = PackageInfoMapper.getSrPackageName(uid, parsedStart.processName);
                                }
                                if (!TextUtils.isEmpty(packageName)) {
                                    Log.i("MC_REDIRECT", "[AMLogsObserver] Process start detected: pkg=" + packageName + " pid=" + parsedStart.pid + " uid=" + uid);
                                    Log.i("MC_REDIRECT", "[AMLogsObserver] Triggering bindMount for " + packageName);
                                    getMounter().bindMountAsync(packageName, parsedStart.pid, uid);
                                }
                            } else {
                                final var parsedKilling = parseKillingLine(
                                        line, new String(amKilling), new String(phantomProcessRecord));
                                if (parsedKilling == null) {
                                    continue;
                                }
                                final var uid = PackageInfoMapper.getUid(parsedKilling.principal);
                                if (!isMounterActiveForUid(uid)) {
                                    continue;
                                }
                                final var packageName = PackageInfoMapper.getPackageName(uid, parsedKilling.processName);
                                if (!TextUtils.isEmpty(packageName)) {
                                    getMounter().notifyProcessKilled(packageName, parsedKilling.pid);
                                }
                            }
                        } catch (StringIndexOutOfBoundsException e) {
                            Log.e("ActivityManagerLogsObserver", line, e);
                            if (BuildConfig.DEBUG) {
                                throw e;
                            }
                        }
                    }
                    reader.close();
                    Thread.sleep(5000);
                }
            } catch (final IOException | InterruptedException e) {
                Log.e("ActivityManagerLogsObserver", "Logcat reading loop exited unexpectedly", e);
            } finally {
                // 先终结子进程再广播：回收必须在服务退出路径上同步完成，
                // 不能依赖另一个线程的 finally 时机（System.exit(0) 会抢先）。
                stopLogcatProcess();
                // 仅在非主动停止时广播。主动停止（用户停止服务 / 服务退出）时，
                // 该广播只是退出副产品；app 侧收到后因 server 已死而查不到
                // serverException，会把"用户停止服务"误报为"日志猫已退出"。
                // 意外死亡（logcat 读取异常）不走 onDestroy，标志为 false，仍会广播。
                if (!mStopping) {
                    // 广播失败不得影响子进程回收：原实现把广播放在 destroy 之前，
                    // 广播抛异常会直接跳过回收。
                    try {
                        mServer.noticeDispatcher.broadcastIntent(broadcastIntent ->
                                broadcastIntent.setAction(ServerConstants.ACTION_LOGCAT_SHUTDOWN)
                        );
                    } catch (final Throwable t) {
                        Log.w("ActivityManagerLogsObserver", "broadcast logcat shutdown failed", t);
                    }
                }
                mExecutor.shutdown();
            }
        });
    }

    /**
     * 纯 JVM 数字判定（android.text.TextUtils 在单测不可 mock）。
     * 空串返回 false：原 TextUtils 空串语义会导致 parseInt 抛 NumberFormatException，
     * 该异常不在外层 StringIndexOutOfBounds 捕获内，会直接杀死单线程观察者。
     */
    static boolean isDigitsOnly(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean hasAmStart() {
        return mHasAmStart;
    }

    public long getStartAtMs() {
        return mStartAtMs;
    }

    public long getLastReadAtMs() {
        return mLastReadAtMs;
    }

    public boolean isLogcatShutdown() {
        return mExecutor.isShutdown();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 主动停止标记：区分"观察器意外死亡"（需通知 app）与"服务正在停止"
        // （广播只是退出副产品，不应让 app 侧弹"日志猫已退出"）。
        mStopping = true;
        mExecutor.shutdownNow();
        // 同步终结采集子进程：必须在 onDestroy 返回前完成，
        // 因为调用方紧接着 System.exit(0) 终止 JVM，届时另一个线程的
        // finally 块不会再有机会执行 destroy。
        stopLogcatProcess();
    }

    /**
     * 登记当前采集子进程。
     *
     * <p>若停止流程已经开始（{@link #mStopped}），说明本进程是在停止过程中
     * 才被创建出来的——此时必须立即终结它，否则它将成为无人回收的孤儿。
     */
    private synchronized void registerLogcatProcess(final Process process) {
        if (mStopped) {
            destroyProcess(process);
            return;
        }
        mLogcatProcess = process;
    }

    /**
     * 同步终结采集子进程。
     *
     * <p>幂等：重复调用时无副作用。停止标记置位后，停止期间新创建的
     * 子进程会被 {@link #registerLogcatProcess} 立即终结。
     */
    private synchronized void stopLogcatProcess() {
        mStopped = true;
        final var process = mLogcatProcess;
        mLogcatProcess = null;
        if (process != null) {
            destroyProcess(process);
        }
    }

    /**
     * 终结一个 logcat 子进程：先 SIGTERM 优雅退出，超时后 SIGKILL 兜底。
     *
     * <p>每步独立兜底异常：回收失败不得向上抛出，否则会中断服务退出流程。
     */
    private static void destroyProcess(final Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (final RuntimeException e) {
            Log.w("ActivityManagerLogsObserver", "destroy logcat process failed", e);
            process.destroyForcibly();
        }
    }
}
