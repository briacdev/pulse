package com.pulse.agent.instrumentation;

final class HttpAdviceBridge {

    private HttpAdviceBridge() {
    }

    static void onEnter(Object[] args) {
        Object request = firstArg(args);
        Object effectiveRequest = HttpTransactionSupport.prepareRequest(request);
        replaceFirstArg(args, effectiveRequest);
        HttpTransactionSupport.onEnter(effectiveRequest);
    }

    static void onEnter(Object request) {
        Object effectiveRequest = HttpTransactionSupport.prepareRequest(request);
        HttpTransactionSupport.onEnter(effectiveRequest);
    }

    static void onExit(Object[] args, Throwable thrown) {
        Object request = firstArg(args);
        Object response = secondArg(args);
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    static void onExit(Object request, Object response, Throwable thrown) {
        HttpTransactionSupport.onExit(request, response, thrown);
    }

    private static Object firstArg(Object[] args) {
        return args == null || args.length == 0 ? null : args[0];
    }

    private static Object secondArg(Object[] args) {
        return args == null || args.length < 2 ? null : args[1];
    }

    private static void replaceFirstArg(Object[] args, Object replacement) {
        if (args != null && args.length > 0) {
            args[0] = replacement;
        }
    }
}
