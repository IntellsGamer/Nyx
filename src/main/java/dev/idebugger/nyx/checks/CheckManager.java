package dev.idebugger.nyx.checks;

import java.util.HashMap;
import java.util.Map;

public class CheckManager {

    private final Map<Class<?>, Check> checks = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T extends Check> T getCheck(Class<T> clazz) {
        return (T) checks.get(clazz);
    }

    public void register(Check check) {
        checks.put(check.getClass(), check);
    }

    public Map<Class<?>, Check> getChecks() {
        return checks;
    }
}
