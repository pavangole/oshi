/*
 * Copyright 2021-2022 The OSHI Project Contributors
 * SPDX-License-Identifier: MIT
 */
package oshi.software.os.unix.openbsd;

import static oshi.jna.platform.unix.OpenBsdLibc.CTL_KERN;
import static oshi.jna.platform.unix.OpenBsdLibc.KERN_OSRELEASE;
import static oshi.jna.platform.unix.OpenBsdLibc.KERN_OSTYPE;
import static oshi.jna.platform.unix.OpenBsdLibc.KERN_VERSION;
import static oshi.software.os.OSService.State.RUNNING;
import static oshi.software.os.OSService.State.STOPPED;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oshi.annotation.concurrent.ThreadSafe;
import oshi.jna.platform.unix.OpenBsdLibc;
import oshi.software.common.AbstractOperatingSystem;
import oshi.software.os.FileSystem;
import oshi.software.os.InternetProtocolStats;
import oshi.software.os.NetworkParams;
import oshi.software.os.OSProcess;
import oshi.software.os.OSService;
import oshi.util.ExecutingCommand;
import oshi.util.ParseUtil;
import oshi.util.platform.unix.openbsd.OpenBsdSysctlUtil;
import oshi.util.tuples.Pair;

/**
 * OpenBsd is a free and open-source Unix-like operating system descended from the Berkeley Software Distribution (BSD),
 * which was based on Research Unix.
 */
@ThreadSafe
public class OpenBsdOperatingSystem extends AbstractOperatingSystem {

    private static final Logger LOG = LoggerFactory.getLogger(OpenBsdOperatingSystem.class);

    private static final long BOOTTIME = querySystemBootTime();

    /*
     * Package-private for use by OpenBsdOSProcess
     */
    enum PsKeywords {
        STATE, PID, PPID, USER, UID, GROUP, GID, PRI, VSZ, RSS, ETIME, CPUTIME, COMM, MAJFLT, MINFLT, NVCSW, NIVCSW,
        ARGS; // ARGS must always be last
    }

    static final String PS_COMMAND_ARGS = Arrays.stream(PsKeywords.values()).map(Enum::name).map(String::toLowerCase)
            .collect(Collectors.joining(","));

    @Override
    public String queryManufacturer() {
        return "Unix/BSD";
    }

    @Override
    public Pair<String, OSVersionInfo> queryFamilyVersionInfo() {
        int[] mib = new int[2];
        mib[0] = CTL_KERN;
        mib[1] = KERN_OSTYPE;
        String family = OpenBsdSysctlUtil.sysctl(mib, "OpenBSD");
        mib[1] = KERN_OSRELEASE;
        String version = OpenBsdSysctlUtil.sysctl(mib, "");
        mib[1] = KERN_VERSION;
        String versionInfo = OpenBsdSysctlUtil.sysctl(mib, "");
        String buildNumber = versionInfo.split(":")[0].replace(family, "").replace(version, "").trim();

        return new Pair<>(family, new OSVersionInfo(version, null, buildNumber));
    }

    @Override
    protected int queryBitness(int jvmBitness) {
        if (jvmBitness < 64 && ExecutingCommand.getFirstAnswer("uname -m").indexOf("64") == -1) {
            return jvmBitness;
        }
        return 64;
    }

    @Override
    public FileSystem getFileSystem() {
        return new OpenBsdFileSystem();
    }

    @Override
    public InternetProtocolStats getInternetProtocolStats() {
        return new OpenBsdInternetProtocolStats();
    }

    @Override
    public List<OSProcess> queryAllProcesses() {
        return getProcessListFromPS(-1);
    }

    @Override
    public List<OSProcess> queryChildProcesses(int parentPid) {
        List<OSProcess> allProcs = queryAllProcesses();
        Set<Integer> descendantPids = getChildrenOrDescendants(allProcs, parentPid, false);
        return allProcs.stream().filter(p -> descendantPids.contains(p.getProcessID())).collect(Collectors.toList());
    }

    @Override
    public List<OSProcess> queryDescendantProcesses(int parentPid) {
        List<OSProcess> allProcs = queryAllProcesses();
        Set<Integer> descendantPids = getChildrenOrDescendants(allProcs, parentPid, true);
        return allProcs.stream().filter(p -> descendantPids.contains(p.getProcessID())).collect(Collectors.toList());
    }

    @Override
    public OSProcess getProcess(int pid) {
        List<OSProcess> procs = getProcessListFromPS(pid);
        if (procs.isEmpty()) {
            return null;
        }
        return procs.get(0);
    }

    private static List<OSProcess> getProcessListFromPS(int pid) {
        List<OSProcess> procs = new ArrayList<>();
        // https://man.openbsd.org/ps#KEYWORDS
        // missing are threadCount and kernelTime which is included in cputime
        String psCommand = "ps -awwxo " + PS_COMMAND_ARGS;
        if (pid >= 0) {
            psCommand += " -p " + pid;
        }
        List<String> procList = ExecutingCommand.runNative(psCommand);
        if (procList.isEmpty() || procList.size() < 2) {
            return procs;
        }
        // remove header row
        procList.remove(0);
        // Fill list
        for (String proc : procList) {
            Map<PsKeywords, String> psMap = ParseUtil.stringToEnumMap(PsKeywords.class, proc.trim(), ' ');
            // Check if last (thus all) value populated
            if (psMap.containsKey(PsKeywords.ARGS)) {
                procs.add(new OpenBsdOSProcess(
                        pid < 0 ? ParseUtil.parseIntOrDefault(psMap.get(PsKeywords.PID), 0) : pid, psMap));
            }
        }
        return procs;
    }

    @Override
    public int getProcessId() {
        return OpenBsdLibc.INSTANCE.getpid();
    }

    @Override
    public int getProcessCount() {
        List<String> procList = ExecutingCommand.runNative("ps -axo pid");
        if (!procList.isEmpty()) {
            // Subtract 1 for header
            return procList.size() - 1;
        }
        return 0;
    }

    @Override
    public int getThreadCount() {
        // -H "Also display information about kernel visible threads"
        // -k "Also display information about kernel threads"
        // column TID holds thread ID
        List<String> threadList = ExecutingCommand.runNative("ps -axHo tid");
        if (!threadList.isEmpty()) {
            // Subtract 1 for header
            return threadList.size() - 1;
        }
        return 0;
    }

    @Override
    public long getSystemUptime() {
        return System.currentTimeMillis() / 1000 - BOOTTIME;
    }

    @Override
    public long getSystemBootTime() {
        return BOOTTIME;
    }

    private static long querySystemBootTime() {
        // Boot time will be the first consecutive string of digits.
        return ParseUtil.parseLongOrDefault(
                ExecutingCommand.getFirstAnswer("sysctl -n kern.boottime").split(",")[0].replaceAll("\\D", ""),
                System.currentTimeMillis() / 1000);
    }

    @Override
    public NetworkParams getNetworkParams() {
        return new OpenBsdNetworkParams();
    }

    @Override
    public List<OSService> getServices() {
        // Get running services
        List<OSService> services = new ArrayList<>();
        Set<String> running = new HashSet<>();
        for (OSProcess p : getChildProcesses(1, ProcessFiltering.ALL_PROCESSES, ProcessSorting.PID_ASC, 0)) {
            OSService s = new OSService(p.getName(), p.getProcessID(), RUNNING);
            services.add(s);
            running.add(p.getName());
        }
        // Get Directories for stopped services
        File dir = new File("/etc/rc.d");
        File[] listFiles;
        if (dir.exists() && dir.isDirectory() && (listFiles = dir.listFiles()) != null) {
            for (File f : listFiles) {
                String name = f.getName();
                if (!running.contains(name)) {
                    OSService s = new OSService(name, 0, STOPPED);
                    services.add(s);
                }
            }
        } else {
            LOG.error("Directory: /etc/rc.d does not exist");
        }
        return services;
    }
}
