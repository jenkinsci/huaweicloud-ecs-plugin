package io.jenkins.plugins.huaweicloud.util;

import io.jenkins.plugins.huaweicloud.model.ProvisionExcess;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ProvisionExcessController {
    private final ReentrantLock lock = new ReentrantLock();
    private Map<String, ProvisionExcess> runningExcess = new HashMap<>();

    public boolean runPE(ProvisionExcess pe) {
        boolean runSuccess = false;
        lock.lock();
        String key = pe.toString();
        if (!runningExcess.containsKey(key)) {
            runningExcess.put(key, pe);
            runSuccess = true;
        }
        lock.unlock();
        return runSuccess;
    }

    public void donePE(ProvisionExcess pe) {
        lock.lock();
        runningExcess.remove(pe.toString());
        lock.unlock();
    }
}
