package com.fongmi.android.tv.api.hk;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.fongmi.quickjs.utils.Crypto;
import com.fongmi.quickjs.utils.Parser;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.logger.Logger;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.lang.reflect.Type;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 海阔 JS 运行时（M2，设计文档 §4.4）。
 *
 * <ul>
 *   <li>每个规则一个独立 Rhino Context/Scope（隔离变量），跑在单线程 executor 上，API 全部同步实现。</li>
 *   <li>注册 Hiker 方言 API：fetch/post、setResult 系列、parseDom 系列、MY_* 变量、putVar/getVar、setItem/getItem、
 *   toast/log/setError、base64、encodeStr/decodeStr、aes、getResCode/getUrl、require。</li>
 *   <li>preRule 在首次 home/search 前各执行一次（主要用途取 cookie）。</li>
 *   <li>实现 {@link HkSelector.JsEvaluator}，给选择器的 {@code .js:} 值加工提供求值能力。</li>
 * </ul>
 *
 * 注意：lazyRule 回调按"独立作用域"铁律设计——HikerApi 只注入全局函数与形参，不依赖 JS 闭包。
 */
public class HkJsRuntime implements HkSelector.JsEvaluator {

    private static final String TAG = "HkJsRuntime";
    private static final Gson GSON = new Gson();
    private static final Type MAP_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    private final ExecutorService executor;
    private final HkRule rule;
    private final Parser parser;
    private final Map<String, String> vars;
    private final List<HkItem> results;
    /** 明细原始条目收集（parseDetailRaw 用，保留 line/col_type 等扩展字段）。 */
    private final List<Map<String, String>> rawResults = new ArrayList<>();
    private volatile boolean collectRaw;
    /** 规则内会话变量（getMyVar/putMyVar，官方 key 形态为 ruleTitle@key；单规则单实例，等价）。 */
    private final Map<String, String> myVars = new HashMap<>();
    /** 跨规则公共持久化（setPublicItem/getPublicItem）。 */
    private final Map<String, String> publicKv = new HashMap<>();
    /** addListener 注册的事件回调（事件名 → 回调描述）。 */
    private final Map<String, String> listeners = new HashMap<>();
    /** fc/rc 的内存缓存（url → 数据+过期时间）。 */
    private final Map<String, MemCache> memCache = new HashMap<>();
    /** setPreResult 预返回 staging：下一记 setResult/setSearchResult 先清空。 */
    private boolean preResultActive;
    /** refreshPage 请求标记（JS 线程写，宿主线程读后清零）。 */
    private volatile boolean refreshRequested;
    private volatile boolean refreshToTop;
    /** setPageTitle/getPageTitle 的页标题状态。 */
    private String pageTitle = "";
    /** setPagePicUrl：官方 SetPagePicEvent 语义，宿主存字段供上层取用。 */
    private String pagePicUrl = "";
    /** setLastChapterRule：官方存最新章节规则，宿主存字段供上层取用。 */
    private String lastChapterRule = "";
    /** setStrResult/setLastChapterResult：官方 JS 回调字符串结果，宿主存字段供上层取用。 */
    private volatile String strResult = "";
    /** 当前页码（MY_PAGE 注入用）。 */
    private int page = 1;
    /** 列表页上下文（MY_TYPE/MY_CLASS_URL/MY_CLASS_NAME/MY_PARAMS/MY_AREA/MY_YEAR/MY_SORT 注入用）。 */
    private String myType = "";
    private String myClassUrl = "";
    private String myClassName = "";
    private String myParams = "";
    private String myArea = "";
    private String myYear = "";
    private String mySort = "";

    /** evalPrivateJS 的 AES 密钥（官方 AesUtil.decrypt 写死值）。 */
    private static final String AES_PRIVATE_KEY = "hk6666666109";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 内存缓存条目。 */
    private static class MemCache {
        final String data;
        final long expireAt;
        MemCache(String data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }
        boolean alive() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    private Context rhinoCx;
    private ScriptableObject scope;
    private Map<String, String> kv;
    private String error;
    private int resCode;
    private String lastUrl;
    private volatile boolean destroyed;
    private volatile boolean preRuleDone;
    /**
     * 入口口令/输入框一次性 input 覆盖（官方 ArticleListFragment.clickItem 语义）：
     * input 类型条目点击时，把用户在 EditText 里输入的文本作为 input 全局变量注入，
     * 而不是默认的 pageUrl。set 后仅下一次 evalLazy 生效，读后清零。
     */
    private volatile String nextInputOverride;

    /** QuickJS 风格的函数签名（Object[] args -> Object），供 putFunction 注册。 */
    private interface JsFunc {
        Object call(Object[] args);
    }

    /**
     * 把 lambda 注册为 scope 上的全局函数（Rhino BaseFunction）。
     * 8.83 官方语义（JSEngine.smali）：桥接函数内不使用 Context.javaToJS
     * （22 处调用全在 runScript/evalJS 的 scope 全局注入）；Java 返回值靠 Rhino
     * LiveConnect 自动包装。Java null 直接返回（Rhino 转为 JS null，
     * 与官方 STRING/OBJECT 模板的 null 语义一致），不转 undefined。
     */
    private void putFunction(String name, JsFunc fn) {
        putFunction(scope, name, fn);
    }

    private void putFunction(ScriptableObject target, String name, JsFunc fn) {
        ScriptableObject.putProperty(target, name, new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object[] a = args == null ? new Object[0] : args;
                for (int i = 0; i < a.length; i++) {
                    if (a[i] == Context.getUndefinedValue()) a[i] = null;
                }
                return fn.call(a);
            }
        });
    }

    /** 求值（替代 ctx.evaluate）。 */
    private Object evalJs(String js) {
        return rhinoCx.evaluateString(scope, js, "hk", 1, null);
    }

    /** JSON 解析（替代 ctx.parse，即 JSON.parse 语义）：非法 JSON 返回空对象，不抛异常。 */
    private Object parseJson(String json) {
        if (TextUtils.isEmpty(json)) return new NativeObject();
        try {
            Object r = NativeJSON.parse(rhinoCx, scope, json, null);
            return r == null ? new NativeObject() : r;
        } catch (Throwable e) {
            return new NativeObject();
        }
    }

    /** JSON 序列化（替代 JSObject.stringify）。 */
    private String jsStringify(Object value) {
        try {
            Object r = NativeJSON.stringify(rhinoCx, scope, value, null, null);
            if (r == null || r == Context.getUndefinedValue()) return "null";
            return Context.toString(r);
        } catch (Throwable e) {
            return String.valueOf(value);
        }
    }

    /** evaluateString 结果转字符串（undefined/null 归一为空）。 */
    private static String jsResultToString(Object r) {
        if (r == null || r == Context.getUndefinedValue()) return "";
        return String.valueOf(r);
    }

    /** List<String> -> JS 数组（替代 JSUtil.toArray）。 */
    private NativeArray toJsArray(List<String> items) {
        NativeArray arr = new NativeArray(items == null ? 0 : items.size());
        if (items != null) {
            for (int i = 0; i < items.size(); i++) arr.put(i, arr, items.get(i));
        }
        return arr;
    }

    /** byte[] -> JS 数组（替代 JSUtil.toArray）。 */
    private NativeArray toJsArray(byte[] bytes) {
        NativeArray arr = new NativeArray(bytes == null ? 0 : bytes.length);
        if (bytes != null) {
            for (int i = 0; i < bytes.length; i++) arr.put(i, arr, (int) bytes[i]);
        }
        return arr;
    }

    public HkJsRuntime(HkRule rule) {
        this.rule = rule;
        this.executor = Executors.newSingleThreadExecutor();
        this.parser = new Parser();
        this.vars = new HashMap<>();
        this.results = new ArrayList<>();
        this.kv = new HashMap<>();
        loadPublicKv();
    }

    private <T> Future<T> submit(java.util.concurrent.Callable<T> callable) {
        return executor.submit(callable);
    }

    /**
     * 在 JS 线程上初始化：建 Context、注册 API、加载 kv、跑 preRule。
     */
    public void init() throws Exception {
        submit(() -> {
            // Rhino：每个规则独立 Context + Scope（变量隔离），跑在单线程 executor 上；
            // setOptimizationLevel(-1) 禁用优化器（Android 无字节码生成，必须关）
            rhinoCx = Context.enter();
            rhinoCx.setOptimizationLevel(-1);
            scope = rhinoCx.initStandardObjects();
            registerApi();
            // 官方内置 CryptoJS（aes.js）：自动注入为全局，兼容直接使用 CryptoJS 的规则；
            // 库本身幂等（var CryptoJS = CryptoJS || ...），规则再 eval(getCryptoJS()) 无害
            try {
                String cryptoJs = loadAsset("aes.js");
                if (!TextUtils.isEmpty(cryptoJs)) evalJs(cryptoJs);
            } catch (Throwable e) {
                Logger.t(TAG).d("inject CryptoJS failed: " + e.getMessage());
            }
            // 官方内置 Hikerurl.js（$ 工具库）：自动注入为全局，定义 $.lazyRule/$.rule/$.image 等；
            // 官方 JSEngine.allFunctionsEnd 会 eval(getJsPlugin())，规则直接调 $ 方法时也需要
            // Hikerurl.js 引用官方 Java 类 com.example.hikerview..RequireUtils（模块缓存映射），
            // 宿主无此 Java 类时整文件求值失败导致 $ 残缺；先在 JS 层 stub 该命名空间（官方调用处有 try-catch）
            try {
                evalJs(
                    "var com = (typeof com !== 'undefined') ? com : {};\n" +
                    "com.example = com.example || {};\n" +
                    "com.example.hikerview = com.example.hikerview || {};\n" +
                    "com.example.hikerview.ui = com.example.hikerview.ui || {};\n" +
                    "com.example.hikerview.ui.rules = com.example.hikerview.ui.rules || {};\n" +
                    "com.example.hikerview.ui.rules.service = com.example.hikerview.ui.rules.service || {};\n" +
                    "com.example.hikerview.ui.rules.service.require = com.example.hikerview.ui.rules.service.require || {};\n" +
                    "com.example.hikerview.ui.rules.service.require.RequireUtils = " +
                    "com.example.hikerview.ui.rules.service.require.RequireUtils || { generateRequireMap: function() {} };\n"
                );
                String hikerUrlJs = loadAsset("Hikerurl.js");
                if (!TextUtils.isEmpty(hikerUrlJs)) evalJs(hikerUrlJs);
            } catch (Throwable e) {
                Logger.t(TAG).d("inject Hikerurl.js failed: " + e.getMessage());
            }
            loadKv();
            loadConfig();
            runPreRule();
            return null;
        }).get();
    }

    // ================= API 注册 =================

    private void registerApi() {
        // ---- 结果返回 ----
        putFunction("setResult", args -> {
            beginResult();
            collectResult(args);
            return null;
        });
        putFunction("setHomeResult", args -> {
            beginResult();
            collectHomeResult(args);
            return null;
        });
        putFunction("setSearchResult", args -> {
            beginResult();
            collectResult(args);
            return null;
        });
        // setPreResult：预返回（官方要求与 setResult 成对、顺序固定）。
        // 语义：先占位展示，下一记 setResult/setSearchResult 到达时整体替换。
        putFunction("setPreResult", args -> {
            results.clear();
            rawResults.clear();
            collectResult(args);
            preResultActive = true;
            Logger.t(TAG).d("setPreResult: %d items staged", results.size());
            return null;
        });
        putFunction("setError", args -> {
            error = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("setError: %s", error);
            return null;
        });
        // 官方别名：error 即 setError
        putFunction("error", args -> {
            error = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("error: %s", error);
            return null;
        });

        // ---- 网络 ----
        putFunction("fetch", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            // D2：hiker://assets/ 走内置 asset（官方 HttpHelper.fetch 经 fetchByHiker 拦截同款）
            if (url.startsWith("hiker://assets/")) return loadAssetText(url);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : null;
            return fetchSync(url, options);
        });
        putFunction("post", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            options = mergeMethod(options, "post");
            return fetchSync(url, options);
        });
        putFunction("request", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            // hiker://page/<path> 内部协议：从规则 pages 里按 path 取页面规则，返回 {"rule": "..."}
            if (url.startsWith("hiker://page/")) return handlePageRequest(url);
            // D2：hiker://assets/ 走内置 asset（官方 $.require 经 getScript→request() 取脚本同款）
            if (url.startsWith("hiker://assets/")) return loadAssetText(url);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : null;
            return fetchSync(url, options);
        });
        putFunction("getResCode", args -> resCode);
        // 官方别名：getCode 即 getResCode
        putFunction("getCode", args -> resCode);
        putFunction("getUrl", args -> lastUrl == null ? "" : lastUrl);

        // ---- DOM 解析（复用 Parser，即海阔 parseHikerToJq 路径） ----
        putFunction("parseDom", args -> {
            if (args == null || args.length < 2) return "";
            String urlKey = args.length > 2 && args[2] != null ? String.valueOf(args[2]) : (lastUrl == null ? "" : lastUrl);
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), urlKey);
        });
        putFunction("pd", args -> {
            if (args == null || args.length < 2) return "";
            String urlKey = args.length > 2 && args[2] != null ? String.valueOf(args[2]) : (lastUrl == null ? "" : lastUrl);
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), urlKey);
        });
        putFunction("parseDomForHtml", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), "");
        });
        putFunction("pdfh", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), "");
        });
        putFunction("parseDomForArray", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            List<String> items = parser.pdfa(String.valueOf(args[0]), sel(args[1]));
            return toJsArray(items);
        });
        putFunction("pdfa", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            List<String> items = parser.pdfa(String.valueOf(args[0]), sel(args[1]));
            return toJsArray(items);
        });

        // ---- 存储 ----
        putFunction("putVar", args -> {
            if (args != null && args.length > 1) vars.put(String.valueOf(args[0]), String.valueOf(args[1]));
            return null;
        });
        // 官方 putVar2：值非字符串时 JSON 序列化后存入（与 putVar 区别仅在此）
        putFunction("putVar2", args -> {
            if (args == null || args.length < 2) return null;
            String k = String.valueOf(args[0]);
            if (TextUtils.isEmpty(k)) return null;
            Object v = args[1];
            String s;
            if (v instanceof String) s = (String) v;
            else {
                try { s = stringifyArg(v); }
                catch (Throwable ignored) { s = String.valueOf(v); }
            }
            vars.put(k, s);
            return null;
        });
        putFunction("getVar", args -> {
            if (args == null || args.length == 0) return "";
            String v = vars.get(String.valueOf(args[0]));
            // 8.83 官方 getVar：default 为 undefined/null 时返回 ""，不是 "null" 字符串
            if (v == null && args.length > 1 && args[1] != null) v = String.valueOf(args[1]);
            return v == null ? "" : v;
        });
        putFunction("setItem", args -> {
            if (args != null && args.length > 1) {
                kv.put(String.valueOf(args[0]), String.valueOf(args[1]));
                saveKv();
            }
            return null;
        });
        putFunction("getItem", args -> {
            if (args == null || args.length == 0) return "";
            String v = kv.get(String.valueOf(args[0]));
            if (v == null && args.length > 1) v = String.valueOf(args[1]);
            return v == null ? "" : v;
        });

        // ---- 调试 ----
        putFunction("toast", args -> {
            final String msg = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("toast: %s", msg);
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(App.get(), msg, android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable ignored) {
            }
            return null;
        });
        putFunction("log", args -> {
            Logger.t(TAG).d("%s", args != null && args.length > 0 ? String.valueOf(args[0]) : "");
            return null;
        });

        // ---- 交互/配置（海阔规则常用，preRule 里调） ----
        putFunction("confirm", args -> {
            // 官方弹确认框；我们无 UI，直接走 confirm 回调（若有）并返回 true
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String json = stringifyArg(args[0]);
                    Logger.t(TAG).d("confirm: %s", json);
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
        putFunction("initConfig", args -> {
            // initConfig({host: ...})：把配置持久化到 plugins/hk/config/<规则名>.json，供 config.xxx 读取
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String json = stringifyArg(args[0]);
                    if (!TextUtils.isEmpty(json) && json.startsWith("{")) {
                        HkRuleManager.get().saveRuleConfig(rule.getTitle(), json);
                        // 同步刷新当前 ctx 的 config 对象
                        try {
                            Object obj = parseJson(json);
                            ScriptableObject.putProperty(scope, "config", obj);
                        } catch (Throwable ignored) {
                        }
                        Logger.t(TAG).d("initConfig saved for %s", rule.getTitle());
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("initConfig failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("getParam", args -> {
            // getParam('word')：从 MY_URL 的 query 里取值（$('...').rule(fn) 场景）
            if (args == null || args.length == 0) return "";
            String key = String.valueOf(args[0]);
            try {
                String u = lastUrl == null ? "" : lastUrl;
                int q = u.indexOf('?');
                if (q < 0) return "";
                String query = u.substring(q + 1);
                int h = query.indexOf('#');
                if (h >= 0) query = query.substring(0, h);
                for (String kv : query.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && kv.substring(0, eq).equals(key)) {
                        return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                    }
                }
            } catch (Throwable ignored) {
            }
            return "";
        });

        // ---- 编解码 ----
        putFunction("base64Encode", args -> {
            if (args == null || args.length == 0) return "";
            return Util.base64(String.valueOf(args[0]).getBytes(Charset.forName("UTF-8")));
        });
        putFunction("base64Decode", args -> {
            if (args == null || args.length == 0) return "";
            try {
                return new String(Base64.decode(String.valueOf(args[0]), Base64.DEFAULT), Charset.forName("UTF-8"));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("encodeStr", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String charset = args.length > 1 ? String.valueOf(args[1]) : "UTF-8";
                return Util.base64(String.valueOf(args[0]).getBytes(Charset.forName(charset)));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("decodeStr", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String charset = args.length > 1 ? String.valueOf(args[1]) : "UTF-8";
                return new String(Base64.decode(String.valueOf(args[0]), Base64.DEFAULT), Charset.forName(charset));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("aesEncode", args -> {
            if (args == null || args.length < 2) return "";
            String key = String.valueOf(args[1]);
            String iv = args.length > 2 ? String.valueOf(args[2]) : "";
            return Crypto.aes("AES/CBC/PKCS5Padding", true, String.valueOf(args[0]), false, key, iv, true);
        });
        putFunction("aesDecode", args -> {
            if (args == null || args.length < 2) return "";
            String key = String.valueOf(args[1]);
            String iv = args.length > 2 ? String.valueOf(args[2]) : "";
            return Crypto.aes("AES/CBC/PKCS5Padding", false, String.valueOf(args[0]), true, key, iv, false);
        });

        // ---- require：远程库加载（preRule 常用）；本地 libs/<md5(url)>.js 优先（.hkzip 自带库） ----
        putFunction("require", args -> {
            if (args == null || args.length == 0) return null;
            try {
                String libUrl = String.valueOf(args[0]);
                String code = loadLibLocal(libUrl);
                if (code == null) code = fetchSync(libUrl, null);
                if (!TextUtils.isEmpty(code)) evalJs(code);
            } catch (Throwable e) {
                Logger.t(TAG).d("require failed: %s", e.getMessage());
            }
            return null;
        });

        // ---- __hkRequirePage：$.require(path) 的 Java 实现 ----
        // hiker://page/<path>：从规则 pages 按 path 找页面代码，IIFE 包裹求值（防顶层 const 重声明），
        // 页面代码以 $.exports = xxx 导出，JS 层 $.require 直接返回 $.exports（官方同款语义）。
        // 裸页面名（如 $.require("Cate")，无 :// scheme）：官方语义也是先查规则 pages，
        // 找不到再走远程/本地库逻辑。其他路径：复用 require 的远程/本地库逻辑。
        putFunction("__hkRequirePage", args -> {
            if (args == null || args.length == 0) return null;
            String path = String.valueOf(args[0]);
            try {
                String pageCode = null;
                boolean isRulePage = false;
                if (path.startsWith("hiker://assets/")) {
                    // hiker://assets/xxx.js：映射到宿主内置 assets（海阔官方同款内置库，如 crypto-java.js）
                    String assetPath = path.substring("hiker://assets/".length());
                    int q = assetPath.indexOf('?');
                    if (q >= 0) assetPath = assetPath.substring(0, q);
                    int h = assetPath.indexOf('#');
                    if (h >= 0) assetPath = assetPath.substring(0, h);
                    String code = loadAsset(assetPath.trim());
                    if (TextUtils.isEmpty(code)) {
                        Logger.t(TAG).d("$.require: no built-in asset for %s", assetPath);
                        return null;
                    }
                    try {
                        evalJs(stripJsPrefix(code));
                    } catch (Throwable e) {
                        Logger.t(TAG).d("$.require: asset eval failed %s: %s", assetPath, e.getMessage());
                    }
                    return null;
                } else if (path.startsWith("hiker://page/")) {
                    String p = path.substring("hiker://page/".length());
                    int q = p.indexOf('?');
                    if (q >= 0) p = p.substring(0, q);
                    int h = p.indexOf('#');
                    if (h >= 0) p = p.substring(0, h);
                    pageCode = findPageCode(p.trim());
                    isRulePage = true;
                    if (pageCode == null) {
                        Logger.t(TAG).d("$.require: no page for path=%s", p);
                        return null;
                    }
                } else if (path.indexOf("://") < 0) {
                    // 裸页面名：先查规则 pages，找不到再走远程/本地库逻辑
                    pageCode = findPageCode(path.trim());
                    isRulePage = pageCode != null;
                }
                if (isRulePage) {
                    evalJs("(function(){\n" + stripJsPrefix(pageCode) + "\n})();");
                } else {
                    String code = loadLibLocal(path);
                    if (code == null) code = fetchSync(path, null);
                    if (TextUtils.isEmpty(code)) {
                        Logger.t(TAG).d("$.require: empty lib %s", path);
                        return null;
                    }
                    evalJs(stripJsPrefix(code));
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("$.require failed %s: %s", path, e.getMessage());
            }
            return null;
        });

        // ---- 规则内会话变量（getMyVar/putMyVar/clearMyVar/listMyVarKeys） ----
        putFunction("putMyVar", args -> {
            if (args != null && args.length > 1) myVars.put(String.valueOf(args[0]), String.valueOf(args[1]));
            return null;
        });
        putFunction("getMyVar", args -> {
            if (args == null || args.length == 0) return "";
            String v = myVars.get(String.valueOf(args[0]));
            if (v == null && args.length > 1) v = String.valueOf(args[1]);
            return v == null ? "" : v;
        });
        putFunction("clearMyVar", args -> {
            if (args != null && args.length > 0) myVars.remove(String.valueOf(args[0]));
            else myVars.clear();
            return null;
        });
        putFunction("listMyVarKeys", args -> GSON.toJson(new ArrayList<>(myVars.keySet())));
        putFunction("clearVar", args -> {
            vars.clear();
            return null;
        });

        // ---- 跨规则公共持久化（setPublicItem/getPublicItem/clearPublicItem） ----
        putFunction("setPublicItem", args -> {
            if (args != null && args.length > 1) {
                publicKv.put(String.valueOf(args[0]), String.valueOf(args[1]));
                savePublicKv();
            }
            return null;
        });
        putFunction("getPublicItem", args -> {
            if (args == null || args.length == 0) return "";
            String v = publicKv.get(String.valueOf(args[0]));
            if (v == null && args.length > 1) v = String.valueOf(args[1]);
            return v == null ? "" : v;
        });
        putFunction("clearPublicItem", args -> {
            if (args != null && args.length > 0) publicKv.remove(String.valueOf(args[0]));
            else publicKv.clear();
            savePublicKv();
            return null;
        });

        // ---- storage0 对象（官方：支持存储 JSON 对象的存储封装） ----
        // put* 用 stringifyArg 把值（含 JSObject）序列化为 JSON 存；get* 存的是 JSON 对象/数组则解析回 JS 对象返回
        NativeObject storage0 = new NativeObject();
        putFunction(storage0, "putVar", args -> {
            if (args != null && args.length > 1) vars.put(String.valueOf(args[0]), stringifyArg(args[1]));
            return null;
        });
        putFunction(storage0, "getVar", args -> storage0Get(vars, args));
        putFunction(storage0, "putMyVar", args -> {
            if (args != null && args.length > 1) myVars.put(String.valueOf(args[0]), stringifyArg(args[1]));
            return null;
        });
        putFunction(storage0, "getMyVar", args -> storage0Get(myVars, args));
        putFunction(storage0, "setItem", args -> {
            if (args != null && args.length > 1) {
                kv.put(String.valueOf(args[0]), stringifyArg(args[1]));
                saveKv();
            }
            return null;
        });
        putFunction(storage0, "getItem", args -> storage0Get(kv, args));
        putFunction(storage0, "setPublicItem", args -> {
            if (args != null && args.length > 1) {
                publicKv.put(String.valueOf(args[0]), stringifyArg(args[1]));
                savePublicKv();
            }
            return null;
        });
        putFunction(storage0, "getPublicItem", args -> storage0Get(publicKv, args));
        ScriptableObject.putProperty(scope, "storage0", storage0);

        // ---- 当前结果列表的动态修改（官方经 EventBus 改 UI 列表；本宿主直接改 results） ----
        putFunction("updateItem", args -> {
            // updateItem(id, obj) 或 updateItem(obj)（obj.extra.id / obj.url 作 id）
            try {
                String id = null;
                Object obj = null;
                if (args != null && args.length >= 2) {
                    id = String.valueOf(args[0]);
                    obj = args[1];
                } else if (args != null && args.length == 1) {
                    obj = args[0];
                }
                Map<String, Object> m = toStrMap(obj);
                if (id == null && m != null) {
                    id = str(m, "url");
                    if (TextUtils.isEmpty(id)) {
                        Object extra = m.get("extra");
                        if (extra instanceof Map) id = str((Map<String, Object>) extra, "id");
                    }
                }
                if (!TextUtils.isEmpty(id) && m != null) {
                    int idx = findResultIndex(id);
                    if (idx >= 0) {
                        mergeItem(results.get(idx), m);
                        Logger.t(TAG).d("updateItem: %s", id);
                    }
                    if (collectRaw) {
                        for (Map<String, String> raw : rawResults) {
                            if (id.equals(raw.get("url"))) {
                                for (Map.Entry<String, Object> e : m.entrySet()) {
                                    if (!"extra".equals(e.getKey())) raw.put(e.getKey(), String.valueOf(e.getValue()));
                                }
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("updateItem failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("addItemAfter", args -> {
            addItemAt(args, true);
            return null;
        });
        putFunction("addItemBefore", args -> {
            addItemAt(args, false);
            return null;
        });
        putFunction("deleteItem", args -> {
            if (args != null && args.length > 0) {
                String id = String.valueOf(args[0]);
                int idx = findResultIndex(id);
                if (idx >= 0) results.remove(idx);
                if (collectRaw) rawResults.removeIf(r -> id.equals(r.get("url")));
                Logger.t(TAG).d("deleteItem: %s", id);
            }
            return null;
        });
        putFunction("deleteItemByCls", args -> {
            if (args != null && args.length > 0) {
                String cls = String.valueOf(args[0]);
                results.removeIf(it -> cls.equals(it.getColType()));
                if (collectRaw) rawResults.removeIf(r -> cls.equals(r.get("col_type")));
                Logger.t(TAG).d("deleteItemByCls: %s", cls);
            }
            return null;
        });
        // 官方 clearItem(key) 清的是规则持久化存储里的 key；本宿主 kv.json 即规则存储，对其删 key 等价。
        putFunction("clearItem", args -> {
            if (args != null && args.length > 0 && args[0] != null) {
                kv.remove(String.valueOf(args[0]));
                saveKv();
                Logger.t(TAG).d("clearItem: %s", args[0]);
            }
            return null;
        });

        // ---- 私有加密 JS：AES/ECB/PKCS5Padding，key=hk6666666109 补 0 到 32 字节，base64 输入 ----
        putFunction("evalPrivateJS", args -> {
            if (args == null || args.length == 0) return null;
            try {
                String plain = aesDecryptECB(String.valueOf(args[0]).trim(), AES_PRIVATE_KEY);
                if (TextUtils.isEmpty(plain)) return null;
                return evalJs(plain);
            } catch (Throwable e) {
                Logger.t(TAG).d("evalPrivateJS failed: %s", e.getMessage());
                return null;
            }
        });

        // ---- 带缓存的网络（fc=fetchCache，rc=requireCache，key=url，hours 小时过期） ----
        putFunction("fc", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            return memCached("fc:" + url, hours, () -> fetchSync(url, null));
        });
        // 官方全名：fetchCache 即 fc
        putFunction("fetchCache", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            return memCached("fc:" + url, hours, () -> fetchSync(url, null));
        });
        putFunction("rc", args -> {
            if (args == null || args.length == 0) return null;
            String url = String.valueOf(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            String code = memCached("rc:" + url, hours, () -> {
                String c = loadLibLocal(url);
                return c == null ? fetchSync(url, null) : c;
            });
            try {
                if (!TextUtils.isEmpty(code)) evalJs(stripJsPrefix(code));
            } catch (Throwable e) {
                Logger.t(TAG).d("rc evaluate failed: %s", e.getMessage());
            }
            return null;
        });
        // 官方全名：requireCache 即 rc
        putFunction("requireCache", args -> {
            if (args == null || args.length == 0) return null;
            String url = String.valueOf(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            String code = memCached("rc:" + url, hours, () -> {
                String c = loadLibLocal(url);
                return c == null ? fetchSync(url, null) : c;
            });
            try {
                if (!TextUtils.isEmpty(code)) evalJs(stripJsPrefix(code));
            } catch (Throwable e) {
                Logger.t(TAG).d("requireCache evaluate failed: %s", e.getMessage());
            }
            return null;
        });
        // ---- 批量（bf=batchFetch 并发取回 body 数组；be=batchExecute 预加载，宿主 no-op；bcm 返回原地址） ----
        putFunction("bf", args -> {
            if (args == null || args.length == 0) return "[]";
            try {
                String json = args[0] instanceof NativeArray ? jsStringify(args[0]) : String.valueOf(args[0]);
                List<Object> list = GSON.fromJson(json.trim().startsWith("[") ? json : "[]",
                        new com.google.gson.reflect.TypeToken<List<Object>>() {}.getType());
                if (list == null || list.isEmpty()) return "[]";
                int n = list.size();
                int threads = 4;
                if (args.length > 1) {
                    try { threads = Math.max(1, (int) toDouble(args[1])); } catch (Throwable ignored) {}
                }
                threads = Math.min(n, threads);
                java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<String>> futures = new ArrayList<>(n);
                    for (Object o : list) {
                        final String u;
                        final String opt;
                        if (o instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) o;
                            u = String.valueOf(m.get("url"));
                            Object op = m.get("options");
                            opt = op == null ? null : GSON.toJson(op);
                        } else {
                            u = String.valueOf(o);
                            opt = null;
                        }
                        futures.add(pool.submit(() -> fetchSync(u, opt)));
                    }
                    List<String> out = new ArrayList<>(n);
                    for (Future<String> f : futures) {
                        try { out.add(f.get(30, TimeUnit.SECONDS)); }
                        catch (Throwable ignored) { out.add(""); }
                    }
                    return GSON.toJson(out);
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("bf failed: %s", e.getMessage());
                return "[]";
            }
        });
        // 官方全名：batchFetch 即 bf（并发取回 body 数组）
        putFunction("batchFetch", args -> {
            if (args == null || args.length == 0) return "[]";
            try {
                String json = args[0] instanceof NativeArray ? jsStringify(args[0]) : String.valueOf(args[0]);
                List<Object> list = GSON.fromJson(json.trim().startsWith("[") ? json : "[]",
                        new com.google.gson.reflect.TypeToken<List<Object>>() {}.getType());
                if (list == null || list.isEmpty()) return "[]";
                int n = list.size();
                int threads = 4;
                if (args.length > 1) {
                    try { threads = Math.max(1, (int) toDouble(args[1])); } catch (Throwable ignored) {}
                }
                threads = Math.min(n, threads);
                java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<String>> futures = new ArrayList<>(n);
                    for (Object o : list) {
                        final String u;
                        final String opt;
                        if (o instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) o;
                            u = String.valueOf(m.get("url"));
                            Object op = m.get("options");
                            opt = op == null ? null : GSON.toJson(op);
                        } else {
                            u = String.valueOf(o);
                            opt = null;
                        }
                        futures.add(pool.submit(() -> fetchSync(u, opt)));
                    }
                    List<String> out = new ArrayList<>(n);
                    for (Future<String> f : futures) {
                        try { out.add(f.get(30, TimeUnit.SECONDS)); }
                        catch (Throwable ignored) { out.add(""); }
                    }
                    return GSON.toJson(out);
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("batchFetch failed: %s", e.getMessage());
                return "[]";
            }
        });
        putFunction("be", args -> {
            Logger.t(TAG).d("be(batchExecute): no-op in host");
            return null;
        });
        // 官方全名：batchExecute 即 be（预加载，宿主 no-op）
        putFunction("batchExecute", args -> {
            Logger.t(TAG).d("batchExecute: no-op in host");
            return null;
        });
        putFunction("bcm", args -> {
            String url = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("bcm: passthrough %s", url);
            return url;
        });
        // 官方全名：batchCacheM3u8 即 bcm（返回原地址，宿主不做真实缓存）
        putFunction("batchCacheM3u8", args -> {
            String url = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("batchCacheM3u8: passthrough %s", url);
            return url;
        });
        // ---- PC 模式请求（桌面 UA）与 postRequest（即 post） ----
        putFunction("fetchPC", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            return fetchSync(url, mergeDesktopUa(options));
        });
        // 官方 postPC：桌面 UA 的 POST
        putFunction("postPC", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            options = mergeMethod(options, "post");
            return fetchSync(url, mergeDesktopUa(options));
        });
        // 官方 fetchCookie(url, options)：带 withHeaders 抓取，返回 set-cookie
        putFunction("fetchCookie", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String url = String.valueOf(args[0]);
                String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
                // 强制 withHeaders
                if (!options.contains("withHeaders")) {
                    options = options.trim();
                    if (options.endsWith("}")) options = options.substring(0, options.length() - 1) + ",\"withHeaders\":true}";
                    else options = "{\"withHeaders\":true}";
                }
                String result = fetchSync(url, options);
                if (TextUtils.isEmpty(result)) return "";
                Map<String, Object> map = GSON.fromJson(result, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (map == null) return "";
                Object headers = map.get("headers");
                if (!(headers instanceof Map)) return "";
                Map<String, Object> hm = (Map<String, Object>) headers;
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, Object> e : hm.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie") && e.getValue() != null) {
                        String v = String.valueOf(e.getValue());
                        // 取 cookie 名值对（分号前）
                        int semi = v.indexOf(';');
                        cookies.add(semi > 0 ? v.substring(0, semi).trim() : v.trim());
                    }
                }
                return TextUtils.join("; ", cookies);
            } catch (Throwable e) {
                Logger.t(TAG).d("fetchCookie failed: %s", e.getMessage());
                return "";
            }
        });
        putFunction("postRequest", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            return fetchSync(url, mergeMethod(options, "post"));
        });
        // fcbw/ewr：WebView 取码与 Web 规则执行，宿主不支持，走空
        stub("fcbw", "");
        stub("ewr", "");
        // 官方全名 stub
        stub("fetchCodeByWebView", "");
        stub("executeWebRule", "");
        stub("cacheCode0", null);
        // setPageReverse：官方设置页面倒序标记；宿主存 kv 供规则自查
        putFunction("setPageReverse", args -> {
            try {
                boolean rev = args != null && args.length > 0 && Boolean.parseBoolean(String.valueOf(args[0]));
                kv.put("__pageReverse", rev ? "1" : "0");
                saveKv();
            } catch (Throwable ignored) {
            }
            return null;
        });

        // ---- 文件（作用域限定 App 文件目录；hiker://files/ 映射到 filesDir） ----
        putFunction("writeFile", args -> {
            if (args != null && args.length > 1) writeFileContent(resolveHikerPath(String.valueOf(args[0])), String.valueOf(args[1]));
            return null;
        });
        putFunction("readFile", args -> {
            if (args == null || args.length == 0) return "";
            return readFileContent(resolveHikerPath(String.valueOf(args[0])));
        });
        putFunction("fileExist", args -> {
            if (args == null || args.length == 0) return "false";
            // 官方返回 STRING "true"/"false"
            return new File(resolveHikerPath(String.valueOf(args[0]))).exists() ? "true" : "false";
        });
        // 官方别名：exist 即 fileExist
        putFunction("exist", args -> {
            if (args == null || args.length == 0) return "false";
            return new File(resolveHikerPath(String.valueOf(args[0]))).exists() ? "true" : "false";
        });
        putFunction("getPath", args -> {
            if (args == null || args.length == 0) return "";
            return resolveHikerPath(String.valueOf(args[0]));
        });
        putFunction("saveFile", args -> {
            // saveFile(name, content, mode)：存到规则数据目录
            if (args != null && args.length > 1) {
                File f = new File(HkRuleManager.get().getDataDir(rule.getTitle()), String.valueOf(args[0]));
                writeFileContent(f.getAbsolutePath(), String.valueOf(args[1]));
            }
            return null;
        });
        putFunction("deleteFile", args -> {
            try {
                if (args != null && args.length > 0) new File(resolveHikerPath(String.valueOf(args[0]))).delete();
            } catch (Throwable ignored) {}
            return null;
        });
        putFunction("downloadFile", args -> {
            if (args == null || args.length < 2) return null;
            try {
                String url = String.valueOf(args[0]);
                String dest = resolveHikerPath(String.valueOf(args[1]));
                File f = new File(dest);
                if (f.isDirectory() || dest.endsWith("/")) {
                    String name = url.replaceAll("[?#].*$", "").replaceAll("^.*/", "");
                    if (TextUtils.isEmpty(name)) name = "download.bin";
                    f = new File(f, name);
                }
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                try (okhttp3.Response res = com.fongmi.quickjs.utils.Connect.to(url,
                        com.fongmi.quickjs.bean.Req.objectFrom("{}")).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        java.nio.file.Files.write(f.toPath(), res.body().bytes());
                        Logger.t(TAG).d("downloadFile ok: %s", f.getAbsolutePath());
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("downloadFile failed: %s", e.getMessage());
            }
            return null;
        });

        // ---- 剪贴板 / 分享 ----
        putFunction("copy", args -> {
            try {
                if (args != null && args.length > 0) {
                    ClipboardManager cm = (ClipboardManager) App.get().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("hk", String.valueOf(args[0])));
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("copy failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("parsePaste", args -> {
            // 解析分享文本：云口令则拉取规则 JSON 返回，否则原文返回
            if (args == null || args.length == 0) return "";
            String t = String.valueOf(args[0]).trim();
            try {
                if (HkRuleManager.isCloudCode(t)) {
                    HkRule r = HkRuleManager.get().importByCloudCode(t);
                    Logger.t(TAG).d("parsePaste: cloud code imported %s", r.getTitle());
                    return GSON.toJson(r);
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("parsePaste cloud failed: %s", e.getMessage());
            }
            return t;
        });
        putFunction("getPastes", args -> "[]");
        putFunction("sharePaste", args -> {
            Logger.t(TAG).d("sharePaste: no-op in host");
            return "";
        });

        // ---- m3u8 ----
        putFunction("fixM3u8", args -> {
            if (args == null || args.length < 2) return args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            return fixM3u8Content(String.valueOf(args[0]), String.valueOf(args[1]));
        });
        putFunction("cacheM3u8", args -> {
            String url = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            Logger.t(TAG).d("cacheM3u8: passthrough %s", url);
            return url;
        });
        putFunction("proxyClearM3u8", args -> "");

        // ---- 页面信息 ----
        putFunction("setPageTitle", args -> {
            if (args != null && args.length > 0) pageTitle = String.valueOf(args[0]);
            return null;
        });
        putFunction("getPageTitle", args -> pageTitle);
        putFunction("getHome", args -> {
            if (args == null || args.length == 0) return "";
            return homeOf(String.valueOf(args[0]));
        });
        putFunction("buildUrl", args -> {
            if (args == null || args.length == 0) return "";
            String url = String.valueOf(args[0]);
            if (args.length < 2 || args[1] == null) return url;
            try {
                String json = stringifyArg(args[1]);
                Map<String, Object> map = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (map == null || map.isEmpty()) return url;
                StringBuilder sb = new StringBuilder(url);
                boolean hasQ = url.contains("?");
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    if (e.getValue() == null) continue;
                    sb.append(hasQ ? '&' : '?');
                    hasQ = true;
                    sb.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"));
                    sb.append('=');
                    sb.append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
                }
                return sb.toString();
            } catch (Throwable ex) {
                return url;
            }
        });
        putFunction("joinUrl", args -> {
            if (args == null || args.length < 2) return args != null && args.length > 0 ? String.valueOf(args[0]) : "";
            try {
                return new java.net.URI(String.valueOf(args[0])).resolve(String.valueOf(args[1])).toString();
            } catch (Throwable e) {
                return String.valueOf(args[1]);
            }
        });
        putFunction("getColTypes", args -> GSON.toJson(COL_TYPES));

        // ---- 编解码补充 ----
        putFunction("md5", args -> {
            if (args == null || args.length == 0) return "";
            String v = Util.md5(String.valueOf(args[0]));
            return v == null ? "" : v;
        });
        // 官方 _base64 全局：8.83 JSEngine 注入的 MyBase64 实例（DEFAULT/NO_WRAP 常量 + 编解码方法），
        // 规则如 CryptoUtil.Data.parseBase64(s, _base64.NO_WRAP) 依赖其存在
        putFunction("__b64encodeToString", args -> {
            if (args == null || args.length == 0) return "";
            try {
                byte[] data = args[0] instanceof byte[] ? (byte[]) args[0] : String.valueOf(args[0]).getBytes("UTF-8");
                int flags = args.length > 1 ? (int) Double.parseDouble(String.valueOf(args[1])) : android.util.Base64.DEFAULT;
                return android.util.Base64.encodeToString(data, flags);
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("__b64decode", args -> {
            if (args == null || args.length == 0) return toJsArray(new byte[0]);
            try {
                int flags = args.length > 1 ? (int) Double.parseDouble(String.valueOf(args[1])) : android.util.Base64.DEFAULT;
                return toJsArray(android.util.Base64.decode(String.valueOf(args[0]), flags));
            } catch (Throwable e) {
                return toJsArray(new byte[0]);
            }
        });
        try {
            evalJs(
                "var _base64 = {\n" +
                "  DEFAULT: 0,\n" +
                "  NO_PADDING: 1,\n" +
                "  NO_WRAP: 2,\n" +
                "  CRLF: 4,\n" +
                "  URL_SAFE: 8,\n" +
                "  encodeToString: function(d, f) { return __b64encodeToString(d, f); },\n" +
                "  decode: function(s, f) { return __b64decode(s, f); },\n" +
                "  encode: function(d, f) { return __b64decode(__b64encodeToString(d, f)); },\n" +
                "  decodeToString: function(s, f) { var b = __b64decode(s, f); var r = ''; for (var i = 0; i < b.length; i++) r += String.fromCharCode(b[i] & 255); return r; }\n" +
                "};\n"
            );
        } catch (Throwable e) {
            Logger.t(TAG).d("_base64 inject failed: " + e.getMessage());
        }
        putFunction("hexToBase64", args -> {
            if (args == null || args.length == 0) return "";
            return hexToB64(String.valueOf(args[0]));
        });
        putFunction("hexToBytes", args -> {
            if (args == null || args.length == 0) return toJsArray(new byte[0]);
            try {
                String h = String.valueOf(args[0]).trim();
                byte[] b = new byte[h.length() / 2];
                for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                return toJsArray(b);
            } catch (Throwable e) {
                return toJsArray(new byte[0]);
            }
        });
        putFunction("rsaEncrypt", args -> {
            if (args == null || args.length < 2) return "";
            return rsaCrypto(true, String.valueOf(args[0]), String.valueOf(args[1]));
        });
        putFunction("rsaDecrypt", args -> {
            if (args == null || args.length < 2) return "";
            return rsaCrypto(false, String.valueOf(args[0]), String.valueOf(args[1]));
        });
        putFunction("getCryptoJS", args -> {
            // 官方语义：返回内置 CryptoJS 库（aes.js）源码，规则 eval(getCryptoJS()) 后使用
            String code = loadAsset("aes.js");
            if (code == null) {
                Logger.t(TAG).d("getCryptoJS: aes.js missing in assets");
                return "";
            }
            return code;
        });
        putFunction("toCorrectJSONString", args -> {
            if (args == null || args.length == 0) return "";
            String t = String.valueOf(args[0]).trim();
            try {
                GSON.fromJson(t, Object.class);
                return t;
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("justTestSign", args -> {
            Logger.t(TAG).d("justTestSign: no-op in host");
            return "";
        });

        // ---- 网络杂项 ----
        putFunction("getCookie", args -> {
            // 宿主 fetch 未维护 cookie 池，返回空（规则多用 getCookie 做登录态判断，空即未登录）
            Logger.t(TAG).d("getCookie: empty in host");
            return "";
        });
        putFunction("getIP", args -> getLocalIp());
        putFunction("ipping", args -> false);
        putFunction("isLogin", args -> false);

        // ---- 事件监听（官方 onClose 等；宿主仅记录，不触发） ----
        putFunction("addListener", args -> {
            try {
                if (args != null && args.length > 1) {
                    listeners.put(String.valueOf(args[0]), String.valueOf(args[1]));
                    Logger.t(TAG).d("addListener: %s", args[0]);
                }
            } catch (Throwable ignored) {}
            return null;
        });
        // 官方别名：listen 即 addListener
        putFunction("listen", args -> {
            try {
                if (args != null && args.length > 1) listeners.put(String.valueOf(args[0]), String.valueOf(args[1]));
            } catch (Throwable ignored) {}
            return null;
        });

        // ---- 选择弹窗（官方弹 UI；宿主无 UI，no-op） ----
        putFunction("showSelectOptions", args -> {
            Logger.t(TAG).d("showSelectOptions: no-op in host");
            return null;
        });

        // ---- 下划线内部别名（兼容老规则写法） ----
        putFunction("_pd", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), "");
        });
        putFunction("_pdfh", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(String.valueOf(args[0]), sel(args[1]), "");
        });
        putFunction("_pdfa", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            return toJsArray(parser.pdfa(String.valueOf(args[0]), sel(args[1])));
        });
        putFunction("_pdfl", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            return toJsArray(parser.pdfa(String.valueOf(args[0]), sel(args[1])));
        });
        putFunction("_findItem", args -> "");
        // findItemsByCls/deleteItemByCls：按条目 extra.cls 匹配当前已收集结果
        putFunction("_findItemsByCls", args -> {
            try {
                String cls = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                List<Map<String, Object>> out = new ArrayList<>();
                for (HkItem it : results) {
                    if (cls.equals(it.getExtra("cls"))) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("title", it.getTitle());
                        m.put("url", it.getUrl());
                        m.put("pic", it.getPic());
                        m.put("desc", it.getDesc());
                        m.put("col_type", it.getColType());
                        out.add(m);
                    }
                }
                return GSON.toJson(out);
            } catch (Throwable e) {
                return "[]";
            }
        });

        // ---- xpath（宿主 Parser 基于 jsoup，不支持 xpath，走空） ----
        putFunction("xpath", args -> "");
        putFunction("xpa", args -> toJsArray(new ArrayList<>()));
        // 官方全名：xpathArray 即 xpa
        putFunction("xpathArray", args -> toJsArray(new ArrayList<>()));

        // ---- refreshPage：官方刷新当前页（bool 为 true 时回顶）。宿主实现为"刷新请求"
        // 标记：lazyRule 回调（如 tab 切换 setItem 后调 refreshPage(true)）经 evalLazy 求值，
        // HkRouter.evalTab 消费该标记后由上层 loadContent(true) 重刷，语义与官方一致。
        putFunction("refreshPage", args -> {
            refreshRequested = true;
            refreshToTop = args != null && args.length > 0 && Boolean.parseBoolean(String.valueOf(args[0]));
            Logger.t(TAG).d("refreshPage(%s) requested", refreshToTop);
            return null;
        });

        // ---- sleep(ms)：官方真实 API（规则里常被 try/catch 包裹做限速/等待）
        putFunction("sleep", args -> {
            long ms = 0;
            try {
                if (args != null && args.length > 0) ms = Long.parseLong(String.valueOf(args[0]));
            } catch (Throwable ignored) {
            }
            if (ms > 0) {
                try {
                    Thread.sleep(Math.min(ms, 10000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });

        // ---- 其余官方 API：宿主无对应能力，一律安全桩（防 ReferenceError，中断规则） ----
        // 导航/UI 类
        stub("back", null);
        stub("backToHome", null);
        stub("closeMe", null);
        stub("refresh", null);
        stub("setPageParams", null);
        // 官方 setPagePicUrl(title)：发 SetPagePicEvent 更新页面配图；宿主存字段供上层取用
        putFunction("setPagePicUrl", args -> {
            try {
                pagePicUrl = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                Logger.t(TAG).d("setPagePicUrl: %s", pagePicUrl);
            } catch (Throwable e) {
                Logger.t(TAG).d("setPagePicUrl failed: %s", e.getMessage());
            }
            return null;
        });
        stub("showLoading", null);
        stub("hideLoading", null);
        stub("refreshReadData", null);
        stub("refreshVideoSource", null);
        stub("refreshVideoUrl", null);
        stub("refreshX5Desc", null);
        stub("refreshX5WebView", null);
        stub("startQRScanPage", null);
        stub("createQRCode", null);
        stub("createQRCodeToFile", "");
        stub("openAppIntent", false);
        stub("initChaquopy", null);
        // 文件/下载类（官方行为：saveImage 下载图片到 path；requireDownload 文件不存在才下载；
        // deleteCache(c) c=-1 清规则全部缓存否则按 url 清，宿主清内存缓存）
        putFunction("requireDownload", args -> {
            if (args == null || args.length < 2) return null;
            try {
                String url = String.valueOf(args[0]);
                String dest = resolveHikerPath(String.valueOf(args[1]));
                File f = new File(dest);
                if (f.exists()) return null;
                String headers = args.length > 2 && args[2] != null ? stringifyArg(args[2]) : "{}";
                downloadToFile(url, dest, headers);
            } catch (Throwable e) {
                Logger.t(TAG).d("requireDownload failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("saveImage", args -> {
            // 官方 saveImage(url, path)：参数顺序注意是 (url, path)
            if (args == null || args.length < 2) return null;
            try {
                String url = String.valueOf(args[0]);
                String path = String.valueOf(args[1]);
                if (TextUtils.isEmpty(url) || "undefined".equalsIgnoreCase(url)) return null;
                if (TextUtils.isEmpty(path) || "undefined".equalsIgnoreCase(path)) return null;
                downloadToFile(url, resolveHikerPath(path), "{}");
            } catch (Throwable e) {
                Logger.t(TAG).d("saveImage failed: %s", e.getMessage());
            }
            return null;
        });
        stub("copyFiles", null);
        putFunction("deleteCache", args -> {
            try {
                String c = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "-1";
                if ("-1".equals(c) || TextUtils.isEmpty(c)) {
                    memCache.clear();
                    Logger.t(TAG).d("deleteCache: cleared all memCache");
                } else {
                    final String key = c;
                    memCache.keySet().removeIf(k -> k.contains(key));
                    Logger.t(TAG).d("deleteCache: cleared memCache for %s", key);
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("deleteCache failed: %s", e.getMessage());
            }
            return null;
        });
        stub("shareDirectory", null);
        stub("writeHexFile", null);
        // 代理/服务类
        stub("startProxyServer", "");
        stub("registerDNS", null);
        stub("addWebProxyRule", null);
        stub("removeWebProxyRule", null);
        stub("refreshWebProxyRule", null);
        stub("png2Ts", null);
        stub("buildWebDav", null);
        // 阅读类（第二期再做）
        stub("getEpubChapters", "[]");
        stub("getEpubContent0", "");
        stub("getEpubMetadata", "{}");
        // 字符串结果回调：官方 setStrResult(o, callbackKey, ruleKey) 完成 JS 回调；
        // 宿主同步模型无 callbackMap，存 strResult 字段供上层取用（setLastChapterResult 官方即委托 setStrResult）
        putFunction("setStrResult", args -> {
            try {
                strResult = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                Logger.t(TAG).d("setStrResult: %d chars", strResult.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setStrResult failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("setLastChapterResult", args -> {
            try {
                strResult = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                Logger.t(TAG).d("setLastChapterResult: %d chars", strResult.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setLastChapterResult failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("setLastChapterRule", args -> {
            try {
                lastChapterRule = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                Logger.t(TAG).d("setLastChapterRule: %d chars", lastChapterRule.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setLastChapterRule failed: %s", e.getMessage());
            }
            return null;
        });
        // 搜索/隐私
        stub("getSearchMode", "");
        stub("setSearchMode", null);
        stub("searchContains", false);
        stub("checkPrivacyPassword", false);
        stub("getPrivacyPasswordLen", 0);
        // 规则管理（官方：LitePal 规则表；宿主：HkRuleManager 落盘目录）
        putFunction("getLastRules", args -> {
            try {
                int count = 12;
                if (args != null && args.length > 0 && args[0] != null) {
                    try {
                        count = (int) toDouble(args[0]);
                    } catch (Throwable ignored) {
                        return "[]";
                    }
                    if (count == -1) count = Integer.MAX_VALUE;
                }
                File dir = HkRuleManager.get().getDir();
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files == null || files.length == 0) return "[]";
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                List<Object> out = new ArrayList<>();
                for (File f : files) {
                    if (out.size() >= count) break;
                    try {
                        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                        Object o = GSON.fromJson(new String(bytes, Charset.forName("UTF-8")), Object.class);
                        if (o != null) out.add(o);
                    } catch (Throwable ignored) {
                    }
                }
                return GSON.toJson(out);
            } catch (Throwable e) {
                Logger.t(TAG).d("getLastRules failed: %s", e.getMessage());
                return "[]";
            }
        });
        stub("publishRule", null);
        putFunction("getRuleCount", args -> {
            try {
                File dir = HkRuleManager.get().getDir();
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                return String.valueOf(files == null ? 0 : files.length);
            } catch (Throwable e) {
                return "0";
            }
        });
        stub("isVideoOrMusic", false);
        stub("isShorthand", false);
        // 任务调度
        stub("registerTask", null);
        stub("unRegisterTask", null);
        // Java 互操作（宿主不开放）
        stub("getCurrentActivity", null);
        stub("findJavaClass", null);
        stub("loadJavaClass", null);
        // 官方 getPrivateJS(c)：AES 加密（key=AES_DEFAULT_KEY="1234567890kkkk"）；
        // 宿主用同 key 的 AES/ECB/PKCS5Padding 实现（base64 输出），保证宿主内确定性
        putFunction("getPrivateJS", args -> {
            if (args == null || args.length == 0 || args[0] == null) return "";
            try {
                return aesEncryptECB(String.valueOf(args[0]), "1234567890kkkk");
            } catch (Throwable e) {
                Logger.t(TAG).d("getPrivateJS failed: %s", e.getMessage());
                return "";
            }
        });
        // 官方语义：返回内置 Hikerurl.js（$ 工具库）源码，规则 eval(getJsPlugin()) 后使用；
        // init() 已自动注入为全局，规则不 eval 也能用
        putFunction("getJsPlugin", args -> {
            String code = loadAsset("Hikerurl.js");
            if (TextUtils.isEmpty(code)) {
                Logger.t(TAG).d("getJsPlugin: Hikerurl.js missing in assets");
                return "";
            }
            return code;
        });
        // 官方 getJsLazyPlugin()：返回内置 assets/plugin.js 源码
        putFunction("getJsLazyPlugin", args -> {
            String code = loadAsset("plugin.js");
            if (code == null) {
                Logger.t(TAG).d("getJsLazyPlugin: plugin.js missing in assets");
                return "";
            }
            return code;
        });
        stub("getMyType", "");
        stub("getMyCallbackKey", "");
        stub("getMyInput", "");
        stub("getMyJs", "");
        stub("syncExecute", null);
        // 首页子模块
        stub("hasHomeSub", false);
        stub("getHomeSub", "[]");
        // 应用信息（真实现）
        putFunction("getAppVersion", args -> {
            try {
                android.content.pm.PackageInfo pi = App.get().getPackageManager()
                        .getPackageInfo(App.get().getPackageName(), 0);
                return pi.versionName == null ? "" : pi.versionName;
            } catch (Throwable ignored) {
                return "";
            }
        });
        putFunction("getCpuAbi", args -> {
            try {
                String[] abis = android.os.Build.SUPPORTED_ABIS;
                return abis != null && abis.length > 0 ? abis[0] : "";
            } catch (Throwable ignored) {
                return "";
            }
        });
        putFunction("getUaObject", args -> {
            Map<String, String> ua = new HashMap<>();
            ua.put("mobileUa", UA_MOBILE);
            ua.put("pcUa", UA_PC);
            return GSON.toJson(ua);
        });

        // ---- $ 选择器助手：$('').lazyRule(fn) / $('...').rule(fn) / $.toString(fn, ...args) / $.require(path) ----
        // lazyRule 把函数序列化为 @lazyRule=.js:... 字符串，拼到 URL 上由 HkRouter.play() 求值；
        // $.toString 把函数+绑定参数序列化为 js:... 字符串，点击时由 evalLazy 求值（input 为全局）。
        // $.require(path)：hiker://page/<path> 从规则 pages 取页面代码求值返回 $.exports，
        // 其他路径复用 require 的远程/本地库逻辑（__hkRequirePage 为 Java 实现）。
        try {
            evalJs(
                "function $(url, headers) {\n" +
                "  url = (typeof url === 'undefined' || url == null) ? '' : String(url);\n" +
                "  var _headersJson = '{}';\n" +
                "  try {\n" +
                "    if (headers != null) _headersJson = (typeof headers === 'string') ? headers : JSON.stringify(headers);\n" +
                "  } catch (_he) {}\n" +
                "  return {\n" +
                "    lazyRule: function(fn) {\n" +
                "      var _a = [];\n" +
                "      for (var _i = 1; _i < arguments.length; _i++) {\n" +
                "        try { _a.push(JSON.stringify(arguments[_i])); } catch (_e) { _a.push('null'); }\n" +
                "      }\n" +
                "      return url + '@lazyRule=.js:(' + fn.toString() + ')(' + _a.join(',') + ');';\n" +
                "    },\n" +
                "    rule: function(fn) {\n" +
                "      return url + '@rule=js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    image: function(fn) {\n" +
                "      if (typeof fn !== 'function') return url;\n" +
                "      var _a = [];\n" +
                "      for (var _i = 1; _i < arguments.length; _i++) {\n" +
                "        try { _a.push(JSON.stringify(arguments[_i])); } catch (_e) { _a.push('null'); }\n" +
                "      }\n" +
                "      var _mt = '';\\n" +
                "      try { _mt = (typeof MY_RULE !== 'undefined' && MY_RULE && MY_RULE.title) ? MY_RULE.title : ''; } catch (_mte) {}\\n" +
                "      return url + '@headers=' + _headersJson + '@js=MY_TITLE=' + JSON.stringify(_mt) + ';(' + fn.toString() + ')(' + _a.join(',') + ');';\\n" +
                "    },\n" +
                "    confirm: function(fn) {\n" +
                "      return url + '@confirmRule=js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    input: function(fn) {\n" +
                "      return '@inputRule=.js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    b64: function() {\n" +
                "      return $(url, headers);\n" +
                "    },\n" +
                "    x5Rule: function(fn) {\n" +
                "      return '@x5Rule=js:(' + fn.toString() + ')();';\n" +
                "    }\n" +
                "  };\n" +
                "}\n" +
                "$.toString = function(fn) {\n" +
                "  var args = [];\n" +
                "  for (var i = 1; i < arguments.length; i++) {\n" +
                "    try { args.push(JSON.stringify(arguments[i])); } catch (e) { args.push('null'); }\n" +
                "  }\n" +
                "  return 'js:(' + fn.toString() + ')(' + args.join(',') + ');';\n" +
                "};\n" +
                                "var $require = (function() {\n" +
                "    var RequireUtils = com.example.hikerview.ui.rules.service.require.RequireUtils;\n" +
                "    function Module(o) {\n" +
                "        this.id = o.id;\n" +
                "        this.exports = {};\n" +
                "        this.importParam = o.importParam;\n" +
                "        this.modulePath = o.modulePath;\n" +
                "        this.headers = o.headers;\n" +
                "        this.time = o.time;\n" +
                "        this.code = o.code;\n" +
                "    }\n" +
                "    Module._cache = new Map();\n" +
                "    Module._extensions = {\n" +
                "        'json': function(module) {\n" +
                "            var script = getScript(module).trim();\n" +
                "            try {\n" +
                "                module.exports = JSON.parse(script);\n" +
                "            } catch (e) {\n" +
                "                Module._extensions.js(module);\n" +
                "            }\n" +
                "        },\n" +
                "        'js': function(module) {\n" +
                "            var script = getScript(module);\n" +
                "            var templateFn = new Function(\"module\", \"exports\", \"__filename\", script);\n" +
                "            var exports = module.exports;\n" +
                "            templateFn.call(exports, module, exports, module.id);\n" +
                "        }\n" +
                "    };\n" +
                "    Module.prototype.load = function() {\n" +
                "        var $temp = [$.exports, $.importParam];\n" +
                "        var referee = this.exports;\n" +
                "        $.exports = this.exports;\n" +
                "        $.importParam = this.importParam;\n" +
                "        var type = this.id.split(\"?\")[0];\n" +
                "        if (type.endsWith(\".json\")) {\n" +
                "            Module._extensions.json(this);\n" +
                "        } else {\n" +
                "            Module._extensions.js(this);\n" +
                "        }\n" +
                "        if ($.exports !== this.exports && referee === this.exports) {\n" +
                "            this.exports = $.exports;\n" +
                "        }\n" +
                "        $.exports = $temp[0];\n" +
                "        $.importParam = $temp[1];\n" +
                "    };\n" +
                "    function getScript(module) {\n" +
                "        var code = \"\";\n" +
                "        if (module.code !== void 0) return module.code;\n" +
                "        if (module.id.startsWith(\"hiker://page/\")) {\n" +
                "            var codeObject = request(module.id);\n" +
                "            if (!codeObject) throw new Error('Module \"' + module.modulePath + '\" cannot be found.');\n" +
                "            code = JSON.parse(codeObject).rule;\n" +
                "        } else {\n" +
                "            if (fileExist(module.id) || module.id.startsWith(\"hiker://assets/\")) {\n" +
                "                if (module.time) {\n" +
                "                    code = fetchCache(module.modulePath, module.time, module.headers);\n" +
                "                } else {\n" +
                "                    code = request(module.id);\n" +
                "                }\n" +
                "            } else if (module.modulePath.startsWith(\"http\")) {\n" +
                "                code = request(module.modulePath, module.headers);\n" +
                "                if (!isJsCode(code)) {\n" +
                "                    throw new Error('failed to get module \"' + module.modulePath + '\" from the network!');\n" +
                "                }\n" +
                "                writeFile(module.id, code);\n" +
                "            } else {\n" +
                "                throw new Error('Module \"' + module.modulePath + '\" cannot be found.');\n" +
                "            }\n" +
                "            if (module.modulePath.startsWith(\"http\")) {\n" +
                "                try {\n" +
                "                    var title = \"\";\n" +
                "                    if (typeof MY_RULE !== \"undefined\" && MY_RULE != null) {\n" +
                "                        title = MY_RULE.title;\n" +
                "                    } else if (typeof MY_TITLE !== \"undefined\") {\n" +
                "                        title = MY_TITLE;\n" +
                "                    }\n" +
                "                    RequireUtils.generateRequireMap(title, module.modulePath, \"\", getPath(module.id).slice(7));\n" +
                "                } catch (e) {}\n" +
                "            }\n" +
                "        }\n" +
                "        if (code.startsWith(\"js:\")) {\n" +
                "            code = code.slice(3);\n" +
                "        }\n" +
                "        return code;\n" +
                "    }\n" +
                "    function isJsCode(code) {\n" +
                "        if (!code) return false;\n" +
                "        code = code.trim();\n" +
                "        var notJsCode1 = [\"<!DOCTYPE\", \"<html\", \"<?xml\"];\n" +
                "        for (var s of notJsCode1) {\n" +
                "            if (code.startsWith(s)) return false;\n" +
                "        }\n" +
                "        var notJsCode2 = [\"</html>\", \"</rss>\"];\n" +
                "        for (var s of notJsCode2) {\n" +
                "            if (code.endsWith(s)) return false;\n" +
                "        }\n" +
                "        var jsKey = [\"var \", \"let \", \"const \", \"this.\", \"function\", \"eval(\", \"call(\", \"eval (\", \"call (\", \" => \", \")=>\"];\n" +
                "        for (var s of jsKey) {\n" +
                "            if (code.includes(s)) return true;\n" +
                "        }\n" +
                "        return false;\n" +
                "    }\n" +
                "    function require(modulePath, importParam, headers, time) {\n" +
                "        if (typeof headers === \"number\") {\n" +
                "            time = headers;\n" +
                "            headers = undefined;\n" +
                "        }\n" +
                "        modulePath = modulePath || \"\";\n" +
                "        var absPathname = require.resolve(modulePath);\n" +
                "        if (Module._cache.has(absPathname)) {\n" +
                "            return Module._cache.get(absPathname).exports;\n" +
                "        }\n" +
                "        var module = new Module({id: absPathname, modulePath: modulePath, importParam: importParam, headers: headers, time: time});\n" +
                "        module.load();\n" +
                "        Module._cache.set(module.id, module);\n" +
                "        return module.exports;\n" +
                "    }\n" +
                "    require.resolve = function(modulePath) {\n" +
                "        if (modulePath.startsWith(\"../\") || modulePath.startsWith(\"./\")) {\n" +
                "            return joinUrl(\"file:///files/data/\" + MY_RULE.title + \"/\", modulePath).replace(\"file:///\", \"hiker://\");\n" +
                "        } else if (modulePath.startsWith(\"https://\") || modulePath.startsWith(\"http://\")) {\n" +
                "            return \"hiker://files/libs/\" + md5(modulePath) + \".js\";\n" +
                "        } else if (!modulePath.startsWith(\"hiker://\") && !modulePath.startsWith(\"file://\")) {\n" +
                "            return \"hiker://page/\" + modulePath;\n" +
                "        } else {\n" +
                "            return modulePath;\n" +
                "        }\n" +
                "    };\n" +
                "    require.cache = Module._cache;\n" +
                "    require.eval = function(code, eid, importParam) {\n" +
                "        eid = eid || md5(code);\n" +
                "        if (Module._cache.has(eid)) {\n" +
                "            return Module._cache.get(eid).exports;\n" +
                "        }\n" +
                "        var module = new Module({id: eid, importParam: importParam, code: code});\n" +
                "        module.load();\n" +
                "        Module._cache.set(module.id, module);\n" +
                "        return module.exports;\n" +
                "    };\n" +
                "    return require;\n" +
                "})();\n" +
                "$.require = $require;\n" +
                "$.exports = $.exports || {};\n" +
                "$.importParam = $.importParam || null;\n" +
                "\n"
                "function Uint8Array(a) {\n" +
                "  var r = [];\n" +
                "  if (typeof a === 'number') { for (var i = 0; i < a; i++) r.push(0); }\n" +
                "  else if (a && typeof a.length === 'number') { for (var j = 0; j < a.length; j++) r.push(a[j] & 255); }\n" +
                "  return r;\n" +
                "}\n"
            );
        } catch (Throwable e) {
            Logger.t(TAG).d("$ helper failed: %s", e.getMessage());
        }
    }

    /**
     * 安全桩：官方有但宿主无对应能力的 API，返回类型合适的默认值并打日志，
     * 保证任何规则都不会因 ReferenceError 中断。
     */
    private void stub(String name, Object ret) {
        try {
            putFunction(name, args -> {
                Logger.t(TAG).d("stub %s called (no-op in host)", name);
                return ret;
            });
        } catch (Throwable ignored) {
        }
    }


    /**
     * hiker://page/&lt;path&gt; 内部协议：从规则 pages（JSON 数组）里按 path 找页面规则，
     * 返回 {@code {"rule": "..."}} JSON 字符串。官方 JSEngine.fetchByHiker 同款语义。
     */
    private String handlePageRequest(String url) {
        try {
            String path = url.substring("hiker://page/".length());
            // D1：解析 query 中的 rule= 参数（官方 PageParser.parsePageRule：?rule=标题 按标题换规则查库）
            String ruleTitle = null;
            int q = path.indexOf('?');
            if (q >= 0) {
                String query = path.substring(q + 1);
                int hh = query.indexOf('#');
                if (hh >= 0) query = query.substring(0, hh);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                        if ("rule".equals(k)) {
                            ruleTitle = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                            break;
                        }
                    }
                }
                path = path.substring(0, q);
            }
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            path = path.trim();
            // 若指定了 rule= 标题，从目标规则的 pages 里找；找不到标题则回退当前规则
            HkRule targetRule = rule;
            if (ruleTitle != null && !ruleTitle.isEmpty()) {
                HkRule found = HkRuleManager.get().getInstalledRule(ruleTitle);
                if (found != null) targetRule = found;
                else Logger.t(TAG).d("handlePageRequest: rule title not found: %s, fallback to current", ruleTitle);
            }
            String pagesJson = targetRule.getPages();
            if (!TextUtils.isEmpty(pagesJson)) {
                List<Map<String, Object>> pages = GSON.fromJson(pagesJson, MAP_LIST_TYPE);
                if (pages != null) {
                    for (Map<String, Object> p : pages) {
                        if (path.equals(String.valueOf(p.get("path")))) {
                            Map<String, String> out = new HashMap<>();
                            Object r = p.get("rule");
                            out.put("rule", r == null ? "" : String.valueOf(r));
                            return GSON.toJson(out);
                        }
                    }
                }
            }
            Logger.t(TAG).d("handlePageRequest: no page rule for path=%s", path);
        } catch (Throwable e) {
            Logger.t(TAG).d("handlePageRequest failed: %s", e.getMessage());
        }
        return "{}";
    }

    /**
     * setPreResult 之后的第一记 setResult/setSearchResult/setHomeResult：清空预返回占位。
     */
    private void beginResult() {
        if (preResultActive) {
            results.clear();
            rawResults.clear();
            preResultActive = false;
        }
    }

    /**
     * $.require('hiker://page/xxx') 的页面代码查找：从规则 pages（JSON 数组）按 path 取 rule 字段。
     */
    private String findPageCode(String path) {
        try {
            String pagesJson = rule.getPages();
            if (TextUtils.isEmpty(pagesJson)) return null;
            List<Map<String, Object>> pages = GSON.fromJson(pagesJson, MAP_LIST_TYPE);
            if (pages == null) return null;
            for (Map<String, Object> p : pages) {
                if (path.equals(String.valueOf(p.get("path")))) {
                    Object r = p.get("rule");
                    return r == null ? null : String.valueOf(r);
                }
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("findPageCode failed: %s", e.getMessage());
        }
        return null;
    }

    /** 把 JS 传来的对象参数转成 Map<String,Object>（JSObject/stringify/JSON 字符串均可）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toStrMap(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Map) return (Map<String, Object>) o;
            String json = o instanceof NativeObject ? jsStringify(o) : String.valueOf(o).trim();
            if (!json.startsWith("{")) return null;
            return GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        } catch (Throwable e) {
            return null;
        }
    }

    /** 在当前 results 里按 url 找条目下标。 */
    private int findResultIndex(String id) {
        for (int i = 0; i < results.size(); i++) {
            if (id.equals(results.get(i).getUrl())) return i;
        }
        return -1;
    }

    /** updateItem：把 obj 里的 title/url/pic/pic_url/img/desc/col_type 合并进条目。 */
    private void mergeItem(HkItem item, Map<String, Object> m) {
        String t = str(m, "title");
        if (!TextUtils.isEmpty(t)) item.setTitle(t);
        String u = str(m, "url");
        if (!TextUtils.isEmpty(u)) item.setUrl(u);
        String pic = str(m, "pic_url");
        if (TextUtils.isEmpty(pic)) pic = str(m, "img");
        if (TextUtils.isEmpty(pic)) pic = str(m, "pic");
        if (!TextUtils.isEmpty(pic)) item.setPic(pic);
        String d = str(m, "desc");
        if (!TextUtils.isEmpty(d)) item.setDesc(d);
        String c = str(m, "col_type");
        if (!TextUtils.isEmpty(c)) item.setColType(c);
    }

    /** addItemAfter/addItemBefore(id, obj)：在匹配 url 的条目之后/之前插入。 */
    private void addItemAt(Object[] args, boolean after) {
        try {
            if (args == null || args.length < 2) return;
            String id = String.valueOf(args[0]);
            Map<String, Object> m = toStrMap(args[1]);
            if (m == null) return;
            HkItem item = new HkItem();
            mergeItem(item, m);
            if (TextUtils.isEmpty(item.getUrl())) item.setUrl(id);
            int idx = findResultIndex(id);
            if (idx < 0) {
                results.add(item);
            } else {
                results.add(after ? idx + 1 : idx, item);
            }
            Logger.t(TAG).d("addItem%s: %s", after ? "After" : "Before", id);
        } catch (Throwable e) {
            Logger.t(TAG).d("addItemAt failed: %s", e.getMessage());
        }
    }

    /** 官方 AesUtil.decrypt 同款：base64 → AES/ECB/PKCS5Padding，key 补 '0' 到 32 字节。 */
    private static String aesDecryptECB(String b64, String key) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            byte[] keyBytes = new byte[32];
            byte[] kb = key.getBytes(Charset.forName("UTF-8"));
            System.arraycopy(kb, 0, keyBytes, 0, Math.min(kb.length, 32));
            for (int i = kb.length; i < 32; i++) keyBytes[i] = '0';
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
            return new String(cipher.doFinal(data), Charset.forName("UTF-8"));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * getPrivateJS 的加密 counterpart：AES/ECB/PKCS5Padding，key 补 0 到 32 字节，base64 输出。
     * 官方用 AES_DEFAULT_KEY="1234567890kkkk"，宿主取同 key、ECB 模式实现，保证宿主内确定性。
     */
    private static String aesEncryptECB(String plain, String key) {
        try {
            byte[] keyBytes = new byte[32];
            byte[] kb = key.getBytes(Charset.forName("UTF-8"));
            System.arraycopy(kb, 0, keyBytes, 0, Math.min(kb.length, 32));
            for (int i = kb.length; i < 32; i++) keyBytes[i] = '0';
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
            return Base64.encodeToString(cipher.doFinal(plain.getBytes(Charset.forName("UTF-8"))), Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** fc/rc 的内存缓存：hours<=0 不缓存；命中且未过期直接返回。 */
    private String memCached(String key, double hours, java.util.concurrent.Callable<String> loader) {
        try {
            if (hours > 0) {
                MemCache hit = memCache.get(key);
                if (hit != null && hit.alive()) {
                    Logger.t(TAG).d("memCache hit: %s", key);
                    return hit.data;
                }
            }
            String data = loader.call();
            if (data == null) data = "";
            if (hours > 0) memCache.put(key, new MemCache(data, System.currentTimeMillis() + (long) (hours * 3600_000)));
            return data;
        } catch (Throwable e) {
            Logger.t(TAG).d("memCached failed: %s", e.getMessage());
            return "";
        }
    }

    private static double toDouble(Object o) {
        try {
            if (o instanceof Number) return ((Number) o).doubleValue();
            return Double.parseDouble(String.valueOf(o));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** fetchPC：options 里没有 UA 时补桌面 UA（规则自带的 UA 优先保留）。 */
    private static String mergeDesktopUa(String optionsJson) {
        try {
            Map<String, Object> map = GSON.fromJson(
                    TextUtils.isEmpty(optionsJson) ? "{}" : optionsJson,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (map == null) map = new HashMap<>();
            Object headers = map.get("headers");
            Map<String, Object> hm = headers instanceof Map ? (Map<String, Object>) headers : new HashMap<>();
            boolean hasUa = false;
            for (String k : hm.keySet()) {
                if ("user-agent".equalsIgnoreCase(k)) { hasUa = true; break; }
            }
            if (!hasUa) {
                hm.put("User-Agent", UA_PC);
                map.put("headers", hm);
            }
            return GSON.toJson(map);
        } catch (Throwable e) {
            return "{\"headers\":{\"User-Agent\":\"" + UA_PC + "\"}}";
        }
    }

    /**
     * 文件路径解析：hiker://files/... → App filesDir；绝对路径原样；相对路径 → 规则数据目录。
     * 全部钳制在 App 文件目录内。
     */
    private String resolveHikerPath(String p) {
        try {
            if (TextUtils.isEmpty(p)) return "";
            File base = App.get().getFilesDir();
            if (p.startsWith("hiker://files/")) {
                return new File(base, p.substring("hiker://files/".length())).getAbsolutePath();
            }
            if (p.startsWith("file://")) p = p.substring("file://".length());
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(HkRuleManager.get().getDataDir(rule.getTitle()), p);
            return f.getAbsolutePath();
        } catch (Throwable ignored) {
            return p == null ? "" : p;
        }
    }

    private String readFileContent(String absPath) {
        try {
            File f = new File(absPath);
            if (!f.exists() || !f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
        } catch (Throwable e) {
            return "";
        }
    }

    private void writeFileContent(String absPath, String content) {
        try {
            File f = new File(absPath);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), (content == null ? "" : content).getBytes(Charset.forName("UTF-8")));
        } catch (Throwable e) {
            Logger.t(TAG).d("writeFile failed %s: %s", absPath, e.getMessage());
        }
    }

    /** fixM3u8(url, content)：把 m3u8 里相对分片地址按 url 补全。 */
    private static String fixM3u8Content(String url, String content) {
        if (TextUtils.isEmpty(content)) return "";
        try {
            java.net.URI base = new java.net.URI(url);
            StringBuilder sb = new StringBuilder();
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#") && !t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                    try { t = base.resolve(t).toString(); } catch (Throwable ignored) {}
                    sb.append(t).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return content;
        }
    }

    private static String hexToB64(String hex) {
        try {
            String h = hex.trim();
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** RSA 加解密（best-effort）：公钥加密 X.509 / 私钥解密 PKCS8，PKCS1Padding，base64 出入。 */
    private static String rsaCrypto(boolean encrypt, String input, String keyB64) {
        try {
            byte[] keyBytes = Base64.decode(keyB64.trim(), Base64.DEFAULT);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            if (encrypt) {
                java.security.PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(keyBytes));
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, pub);
                return Base64.encodeToString(c.doFinal(input.getBytes(Charset.forName("UTF-8"))), Base64.NO_WRAP);
            } else {
                java.security.PrivateKey prv = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
                c.init(javax.crypto.Cipher.DECRYPT_MODE, prv);
                return new String(c.doFinal(Base64.decode(input.trim(), Base64.DEFAULT)), Charset.forName("UTF-8"));
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress() != null && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ================= setPublicItem/getPublicItem 持久化（跨规则） =================

    private File publicKvFile() {
        File dataDir = HkRuleManager.get().getDataDir(rule.getTitle());
        File parent = dataDir.getParentFile();
        if (parent == null) parent = dataDir;
        return new File(parent, "public_kv.json");
    }

    private void loadPublicKv() {
        try {
            File f = publicKvFile();
            if (!f.exists()) return;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            Map<String, String> map = GSON.fromJson(new String(bytes, "UTF-8"),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            if (map != null) publicKv.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private void savePublicKv() {
        try {
            File f = publicKvFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), GSON.toJson(publicKv).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    /** 官方 ArticleColTypeEnum 的全部 col_type code（getColTypes 用）。 */
    private static final List<String> COL_TYPES = java.util.Arrays.asList(
            "text_1", "text_2", "text_3", "text_4", "text_5", "text_center_1", "text_icon",
            "movie_1", "movie_2", "movie_3", "movie_1_left_pic", "movie_1_vertical_pic",
            "movie_1_vertical_pic_blur", "movie_3_marquee",
            "pic_1", "pic_2", "pic_3", "pic_1_full", "pic_1_center", "pic_3_square", "pic_1_card", "pic_2_card",
            "icon_1_search", "icon_2_round", "icon_3_fill", "icon_4", "icon_small_4", "icon_round_small_4",
            "icon_5_no_crop", "icon_1_left_pic", "icon_2", "icon_3_round_fill", "icon_4_card", "icon_5",
            "icon_round_4", "icon_small_3",
            "rich_text", "long_text", "avatar", "video", "header", "footer", "line", "line_blank",
            "blank_block", "big_blank_block", "big_big_blank_block", "scroll_button", "flex_button", "input",
            "card_pic_1", "card_pic_2", "card_pic_2_2", "card_pic_2_2_left", "card_pic_3", "card_pic_3_center",
            "x5_webview_single"
    );

    /**
     * require() 本地库优先：找规则数据目录 data/&lt;规则名&gt;/libs/&lt;md5(url)&gt;.js，
     * 命中则直接加载（.hkzip 导入时已解压，无网络也能用）；未命中返回 null 走远程。
     */
    private String loadLibLocal(String libUrl) {
        try {
            if (libUrl == null || !libUrl.startsWith("http")) return null;
            String md5 = Util.md5(libUrl);
            if (TextUtils.isEmpty(md5)) return null;
            File f = new File(new File(HkRuleManager.get().getDataDir(rule.getTitle()), "libs"), md5 + ".js");
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String code = new String(bytes, Charset.forName("UTF-8"));
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * hiker://assets/xxx：从宿主内置 assets 读取文件内容（海阔官方内置库同款路径）。
     * 文件不存在返回 null。
     */
    private String loadAsset(String assetPath) {
        try {
            java.io.InputStream is = App.get().getAssets().open(assetPath);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String code = bos.toString("UTF-8");
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * D2：hiker://assets/xxx.js → 读宿主内置 assets 返回文本内容。
     * 供 request()/fetch() 调用（官方 $.require 经 getScript→request() 取脚本，
     * 不是 __hkRequirePage）。去 query/fragment 后按 asset 路径读取。
     * 找不到返回 ""（不抛异常）。
     */
    private String loadAssetText(String url) {
        try {
            String assetPath = url.substring("hiker://assets/".length());
            int q = assetPath.indexOf('?');
            if (q >= 0) assetPath = assetPath.substring(0, q);
            int h = assetPath.indexOf('#');
            if (h >= 0) assetPath = assetPath.substring(0, h);
            String code = loadAsset(assetPath.trim());
            return code == null ? "" : code;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ================= 对外接口 =================

    /**
     * 带 headers 下载文件到绝对路径（saveImage/requireDownload 共用）。
     * headersJson 为 JSON：{headers:{...}} 或直接 {k:v}。
     */
    private void downloadToFile(String url, String dest, String headersJson) {
        if (TextUtils.isEmpty(url)) return;
        try {
            File f = new File(dest);
            if (f.isDirectory() || dest.endsWith("/")) {
                String name = url.replaceAll("[?#].*$", "").replaceAll("^.*/", "");
                if (TextUtils.isEmpty(name)) name = "download.bin";
                f = new File(f, name);
            }
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            String opt = "{\"headers\":{}}";
            try {
                Map<String, Object> m = GSON.fromJson(headersJson,
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (m != null) {
                    Object h = m.get("headers");
                    if (h == null) h = m.get("header");
                    Map<String, Object> hm = h instanceof Map ? (Map<String, Object>) h : m;
                    Map<String, String> flat = new HashMap<>();
                    for (Map.Entry<String, Object> e : hm.entrySet()) {
                        if (e.getValue() != null) flat.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                    Map<String, Object> wrap = new HashMap<>();
                    wrap.put("headers", flat);
                    opt = GSON.toJson(wrap);
                }
            } catch (Throwable ignored) {
            }
            try (okhttp3.Response res = com.fongmi.quickjs.utils.Connect.to(url,
                    com.fongmi.quickjs.bean.Req.objectFrom(opt)).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    java.nio.file.Files.write(f.toPath(), res.body().bytes());
                    Logger.t(TAG).d("downloadToFile ok: %s", f.getAbsolutePath());
                } else {
                    Logger.t(TAG).d("downloadToFile bad response: %s", url);
                }
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("downloadToFile failed %s: %s", url, e.getMessage());
        }
    }

    /**
     * 执行首页/分类的 js: 规则，返回条目列表。
     */
    public List<HkItem> parseList(String jsCode, String myUrl) throws Exception {
        return parseList(jsCode, myUrl, 1);
    }

    /**
     * 执行首页/分类的 js: 规则，返回条目列表（带页码，注入 MY_PAGE）。
     */
    public List<HkItem> parseList(String jsCode, String myUrl, int page) throws Exception {
        return submit(() -> {
            results.clear();
            error = null;
            preResultActive = false;
            this.page = Math.max(1, page);
            setContext(myUrl);
            try {
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                // 之前异常直接上抛到 HkEngine.home() 被吞掉只剩 debug 日志，
                // UI 永远只显示"加载失败"。先把根因记下来，供 getError()/空态展示。
                error = jsErrorMsg(e);
                throw e;
            }
            return drainResults();
        }).get();
    }

    /**
     * 执行搜索的 js: 规则，返回条目列表。
     */
    public List<HkItem> parseSearch(String jsCode, String myUrl, String keyword) throws Exception {
        return parseSearch(jsCode, myUrl, keyword, 1);
    }

    /**
     * 执行搜索的 js: 规则，返回条目列表（带页码，注入 MY_PAGE）。
     */
    public List<HkItem> parseSearch(String jsCode, String myUrl, String keyword, int page) throws Exception {
        return submit(() -> {
            results.clear();
            error = null;
            preResultActive = false;
            this.page = Math.max(1, page);
            setContext(myUrl);
            ScriptableObject.putProperty(scope, "MY_KEYWORD", keyword == null ? "" : keyword);
            try {
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                error = jsErrorMsg(e);
                throw e;
            }
            return drainResults();
        }).get();
    }

    /** setStrResult/setLastChapterResult 存下的字符串结果。 */
    public String getStrResult() {
        return strResult;
    }

    /** setLastChapterRule 存下的最新章节规则。 */
    public String getLastChapterRule() {
        return lastChapterRule;
    }

    /** setPagePicUrl 存下的页面配图。 */
    public String getPagePicUrl() {
        return pagePicUrl;
    }

    /**
     * HkSelector.JsEvaluator 实现：选择器 .js: 值加工。
     * 把取值结果作为全局 input，执行 JS 片段并返回结果字符串。
     */
    @Override
    public String eval(String js, String input) {
        error = null;
        try {
            return submit(() -> {
                ScriptableObject.putProperty(scope, "input", input == null ? "" : input);
                Object r = evalJs(js);
                return jsResultToString(r);
            }).get();
        } catch (Exception e) {
            String msg = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : e.getMessage();
            error = msg == null || msg.isEmpty() ? "JS 执行失败" : msg;
            Logger.t(TAG).d("evalJs failed: %s", e.getMessage());
            return input == null ? "" : input;
        }
    }

    /**
     * M4：lazyRule / 播放 js 的独立作用域求值。
     *
     * <p>按"独立作用域"铁律：只注入全局 Hiker API，外加 {@code MY_URL}/{@code input}
     * 两个形参（均为当前页面地址），不依赖 JS 闭包。返回代码求值结果的字符串；
     * 若求值为空则兜底取 {@code setResult} 收集到的第一条 url。</p>
     */
    public String evalLazy(String jsCode, String pageUrl) throws Exception {
        return submit(() -> {
            error = null;
            setContext(pageUrl);
            // 入口口令/输入框：若有一次性 input 覆盖（用户输入的文本），优先使用；
            // 否则按原逻辑用 pageUrl。读后清零，保证只生效一次。
            String inputVal = nextInputOverride != null ? nextInputOverride : (pageUrl == null ? "" : pageUrl);
            nextInputOverride = null;
            ScriptableObject.putProperty(scope, "input", inputVal);
            Object r = evalJs(stripJsPrefix(jsCode));
            String s = jsResultToString(r).trim();
            if (s.isEmpty() || "undefined".equals(s) || "null".equals(s)) {
                List<HkItem> items = drainResults();
                if (!items.isEmpty() && !TextUtils.isEmpty(items.get(0).getUrl())) {
                    return items.get(0).getUrl().trim();
                }
                return "";
            }
            return s;
        }).get();
    }

    /**
     * 给 HkSelector 配一个带 JS 求值能力的实例（M1 的 null 透传升级为真求值）。
     */
    public HkSelector newSelector() {
        HkSelector selector = new HkSelector();
        selector.setJsEvaluator(this);
        return selector;
    }

    /** 选择器文本预处理：冲突字符解码（？？→?、＆＆→&、；；→;、，，→,）。 */
    private String sel(Object o) {
        return HkSelector.decodeConflict(String.valueOf(o));
    }

    public String getError() {
        return error;
    }

    /**
     * 取异常链上第一条非空 message（ExecutionException 包裝后的根因），
     * 供 parseList/parseSearch 在 JS 求值失败时记录，避免"加载失败"无从查起。
     */
    private static String jsErrorMsg(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String m = t.getMessage();
            if (m != null && !m.trim().isEmpty()) {
                String s = m.trim();
                // Rhino 异常常带堆栈，截短到一行
                int nl = s.indexOf('\n');
                if (nl > 0) s = s.substring(0, nl);
                return s.length() > 160 ? s.substring(0, 160) : s;
            }
            t = t.getCause();
        }
        return e == null ? "" : String.valueOf(e.getClass().getSimpleName());
    }

    /**
     * 取走 refreshPage 请求标记（读后清零）。供 HkRouter.evalTab 等在 JS 求值后检查：
     * 若规则回调里调了 refreshPage，上层应重刷当前列表。
     */
    public boolean consumeRefreshRequest() {
        boolean r = refreshRequested;
        refreshRequested = false;
        return r;
    }

    /** refreshPage 请求标记当前是否为 true（不读后清零，供调用方做"变化检测"）。 */
    public boolean isRefreshRequested() {
        return refreshRequested;
    }

    /**
     * 设置下一次 evalLazy 的 input 覆盖值（入口口令/输入框流程用）。
     * 官方语义：input 条目点击时用户输入的文本作为 input 变量，而非 pageUrl。
     */
    public void setNextInputOverride(String input) {
        this.nextInputOverride = input;
    }

    public boolean isRefreshToTop() {
        return refreshToTop;
    }

    public void destroy() {
        destroyed = true;
        try {
            submit(() -> {
                try {
                    // Rhino Context 与线程绑定：enter/exit 必须在同一线程配对（即本单线程 executor）
                    if (rhinoCx != null) Context.exit();
                } catch (Throwable ignored) {
                }
                rhinoCx = null;
                scope = null;
                return null;
            }).get();
        } catch (Throwable ignored) {
        } finally {
            executor.shutdownNow();
        }
    }

    // ================= 内部实现 =================

    private void setContext(String myUrl) {
        lastUrl = myUrl == null ? "" : myUrl;
        ScriptableObject.putProperty(scope, "MY_URL", lastUrl);
        ScriptableObject.putProperty(scope, "MY_HOME", homeOf(lastUrl));
        ScriptableObject.putProperty(scope, "MY_PAGE", page);
        ScriptableObject.putProperty(scope, "MY_TICKET", "");
        ScriptableObject.putProperty(scope, "MOBILE_UA", UA_MOBILE);
        ScriptableObject.putProperty(scope, "PC_UA", UA_PC);
        // P1：补全官方注入变量
        ScriptableObject.putProperty(scope, "MY_TYPE", myType == null ? "" : myType);
        ScriptableObject.putProperty(scope, "MY_CLASS_URL", myClassUrl == null ? "" : myClassUrl);
        ScriptableObject.putProperty(scope, "MY_CLASS_NAME", myClassName == null ? "" : myClassName);
        ScriptableObject.putProperty(scope, "MY_NAME", rule == null ? "" : rule.getTitle());
        // 官方 generateMyParams：MY_PARAMS 是 JSON 对象不是字符串（空时为 {}）
        try {
            String mp = myParams == null || myParams.isEmpty() ? "{}" : myParams;
            ScriptableObject.putProperty(scope, "MY_PARAMS", parseJson(mp));
        } catch (Throwable ignored) {
            ScriptableObject.putProperty(scope, "MY_PARAMS", new NativeObject());
        }
        ScriptableObject.putProperty(scope, "MY_AREA", myArea == null ? "" : myArea);
        ScriptableObject.putProperty(scope, "MY_YEAR", myYear == null ? "" : myYear);
        ScriptableObject.putProperty(scope, "MY_SORT", mySort == null ? "" : mySort);
        // MY_YEAR_xxx / MY_AREA_xxx / MY_SORT_xxx：按当前取值动态注入（如 MY_YEAR_2024）
        if (myYear != null && !myYear.isEmpty())
            ScriptableObject.putProperty(scope, "MY_YEAR_" + myYear, myYear);
        if (myArea != null && !myArea.isEmpty())
            ScriptableObject.putProperty(scope, "MY_AREA_" + myArea, myArea);
        if (mySort != null && !mySort.isEmpty())
            ScriptableObject.putProperty(scope, "MY_SORT_" + mySort, mySort);
        try {
            Map<String, String> ua = new HashMap<>();
            ua.put("mobileUa", UA_MOBILE);
            ua.put("pcUa", UA_PC);
            ScriptableObject.putProperty(scope, "MY_UA", parseJson(GSON.toJson(ua)));
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", rule == null ? UA_MOBILE : rule.resolvedUa());
            ScriptableObject.putProperty(scope, "MY_HEADERS", parseJson(GSON.toJson(headers)));
        } catch (Throwable ignored) {
        }
        try {
            ScriptableObject.putProperty(scope, "MY_RULE", parseJson(GSON.toJson(rule)));
        } catch (Throwable e) {
            ScriptableObject.putProperty(scope, "MY_RULE", new NativeObject());
        }
    }

    /**
     * 设置列表页上下文（HkEngine.home/search 在 parseList/parseSearch 之前调用），
     * 供 setContext 注入 MY_TYPE/MY_CLASS_URL/MY_CLASS_NAME/MY_PARAMS 等官方变量。
     */
    public void setListContext(String type, String classUrl, String className,
                              String params, String area, String year, String sort) {
        this.myType = type == null ? "" : type;
        this.myClassUrl = classUrl == null ? "" : classUrl;
        this.myClassName = className == null ? "" : className;
        this.myParams = params == null ? "" : params;
        this.myArea = area == null ? "" : area;
        this.myYear = year == null ? "" : year;
        this.mySort = sort == null ? "" : sort;
    }

    /**
     * 设置详情页 params（官方 dealRule 把点击条目的 extra 设为新规则的 params，
     * detail_find_rule 里通过 MY_PARAMS 取用）。extra 为 Map 时序列化为 JSON。
     */
    public void setDetailParams(java.util.Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            this.myParams = "";
        } else {
            try {
                this.myParams = GSON.toJson(extra);
            } catch (Throwable ignored) {
                this.myParams = "";
            }
        }
    }

    private static String homeOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost();
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Throwable e) {
            return url;
        }
    }

    private static String stripJsPrefix(String code) {
        if (code == null) return "";
        String t = code.trim();
        return t.startsWith("js:") ? t.substring(3) : t;
    }

    private void runPreRule() {
        if (preRuleDone) return;
        preRuleDone = true;
        String pre = rule.getPreRule();
        if (TextUtils.isEmpty(pre) || TextUtils.isEmpty(pre.trim())) return;
        try {
            // preRule 的 MY_URL 同样传原始 url（保留 hiker://empty# 前缀）。
            setContext(HkHttp.expandUrl(rule.getUrl(), "", "", "", "", 1, false));
            evalJs(pre.trim().startsWith("js:") ? pre.trim().substring(3) : pre);
            Logger.t(TAG).d("preRule done for %s", rule.getTitle());
        } catch (Throwable e) {
            Logger.t(TAG).d("preRule failed for %s: %s", rule.getTitle(), e.getMessage());
        }
    }

    /**
     * 注入海阔规则配置对象 {@code config}（海阔"长按规则→设置"的 key-value，如 config.host）。
     * 配置持久化在 plugins/hk/config/&lt;规则名&gt;.json；文件不存在时注入空对象，保证
     * {@code config.xxx} 不抛 ReferenceError（取值为 undefined）。
     */
    private void loadConfig() {
        try {
            String json = HkRuleManager.get().loadRuleConfig(rule.getTitle());
            Object obj = parseJson(json);
            ScriptableObject.putProperty(scope, "config", obj);
        } catch (Throwable e) {
            try {
                ScriptableObject.putProperty(scope, "config", new NativeObject());
            } catch (Throwable ignored) {
            }
        }
    }

    private void collectResult(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return;
        try {
            String json = args[0] instanceof NativeArray
                    ? jsStringify(args[0])
                    : String.valueOf(args[0]);
            if (json == null) return;
            String t = json.trim();
            // 官方 callbackHomeResult 兼容：setResult({data:[...]}) 的对象形式。
            // 之前只认顶层数组，对象形式会被 GSON 抛错静默丢弃 → 整个列表为空（"加载失败"）。
            if (t.startsWith("{")) {
                Map<String, Object> obj = GSON.fromJson(t, new TypeToken<Map<String, Object>>() {}.getType());
                Object data = obj == null ? null : obj.get("data");
                if (data instanceof List) {
                    appendResultItems(GSON.toJson(data));
                } else {
                    Logger.t(TAG).d("collectResult: object without data array");
                }
                return;
            }
            appendResultItems(t);
        } catch (Throwable e) {
            Logger.t(TAG).d("collectResult failed: %s", e.getMessage());
        }
    }

    /** 把 JSON 数组字符串解析为条目并追加到 results（collectResult 的实际落子逻辑）。 */
    private void appendResultItems(String jsonArray) {
        List<Map<String, Object>> list = GSON.fromJson(jsonArray, MAP_LIST_TYPE);
        if (list == null) return;
            for (Map<String, Object> m : list) {
                if (collectRaw) {
                    Map<String, String> raw = new HashMap<>();
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        raw.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    rawResults.add(raw);
                    continue;
                }
                HkItem item = new HkItem();
                item.setTitle(str(m, "title"));
                item.setUrl(str(m, "url"));
                String pic = str(m, "pic_url");
                if (TextUtils.isEmpty(pic)) pic = str(m, "img");
                if (TextUtils.isEmpty(pic)) pic = str(m, "pic");
                // 兼容更多封面字段名（规则写法不统一）
                if (TextUtils.isEmpty(pic)) pic = str(m, "image");
                if (TextUtils.isEmpty(pic)) pic = str(m, "cover");
                if (TextUtils.isEmpty(pic)) pic = str(m, "thumbnail");
                if (TextUtils.isEmpty(pic)) pic = str(m, "poster");
                if (TextUtils.isEmpty(pic)) pic = str(m, "imgUrl");
                if (TextUtils.isEmpty(pic)) pic = str(m, "picUrl");
                item.setPic(pic);
                item.setDesc(str(m, "desc"));
                String colType = str(m, "col_type");
                if (TextUtils.isEmpty(colType)) colType = str(m, "colType");
                if (TextUtils.isEmpty(colType)) colType = str(m, "coltype");
                item.setColType(colType);
                // P1：补全官方条目字段 extra/content/line（updateItem/deleteItem/findItemsByCls 用）
                item.setContent(str(m, "content"));
                item.setLine(str(m, "line"));
                Object extraObj = m.get("extra");
                if (extraObj instanceof Map) {
                    Map<String, String> extra = new HashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) extraObj).entrySet()) {
                        extra.put(String.valueOf(e.getKey()),
                                e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    item.setExtra(extra);
                } else {
                    // 兼容：id/cls 等顶层字段也并入 extra，保证按 id/url/cls 匹配可用
                    Map<String, String> extra = new HashMap<>();
                    for (String k : new String[]{"id", "cls", "pageTitle", "newWindow", "lineVisible", "textAlign"}) {
                        String v = str(m, k);
                        if (!v.isEmpty()) extra.put(k, v);
                    }
                    if (!extra.isEmpty()) item.setExtra(extra);
                }
                results.add(item);
            }
    }

    /**
     * 海阔首页专用：{@code setHomeResult(res)}，其中 {@code res = {data: [...]}}，
     * data 是条目数组。直接传数组的写法也兼容。
     */
    private void collectHomeResult(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return;
        try {
            if (args[0] instanceof NativeArray) {
                collectResult(args);
                return;
            }
            String json = args[0] instanceof NativeObject
                    ? jsStringify(args[0])
                    : String.valueOf(args[0]).trim();
            if (json.startsWith("{")) {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> map = GSON.fromJson(json, mapType);
                Object data = map == null ? null : map.get("data");
                if (data instanceof List) {
                    collectResult(new Object[]{GSON.toJson(data)});
                    return;
                }
                Logger.t(TAG).d("setHomeResult: no data array");
                return;
            }
            collectResult(new Object[]{json});
        } catch (Throwable e) {
            Logger.t(TAG).d("collectHomeResult failed: %s", e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private List<HkItem> drainResults() {
        List<HkItem> out = new ArrayList<>(results);
        results.clear();
        return out;
    }

    /**
     * 执行详情的 js: 规则，返回原始条目（含 line/col_type 等扩展字段，M3）。
     * setResult 项约定：title/url/pic_url/desc + 可选 line（线路名）/col_type。
     */
    public List<Map<String, String>> parseDetailRaw(String jsCode, String myUrl) throws Exception {
        return submit(() -> {
            rawResults.clear();
            results.clear();
            error = null;
            collectRaw = true;
            try {
                setContext(myUrl);
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                // 详情页 JS 异常也要记录，供 V4 空态展示真实原因（之前只记 log，用户看到"0条线路"无法反馈）
                error = jsErrorMsg(e);
                throw e;
            } finally {
                collectRaw = false;
            }
            List<Map<String, String>> out = new ArrayList<>(rawResults);
            rawResults.clear();
            results.clear();
            return out;
        }).get();
    }

    private String stringifyArg(Object arg) {
        try {
            if (arg instanceof NativeObject) return jsStringify(arg);
        } catch (Throwable ignored) {
        }
        return String.valueOf(arg);
    }

    /**
     * storage0.get*：存的是 JSON 对象/数组则解析回 JS 对象返回，否则原样返回字符串。
     * 缺省值（第 2 参数）有则原样返回，不做解析。
     */
    private Object storage0Get(java.util.Map<String, String> map, Object[] args) {
        if (args == null || args.length == 0) return "";
        String v = map.get(String.valueOf(args[0]));
        if (v == null) {
            if (args.length > 1 && args[1] != null) return args[1];
            return "";
        }
        String t = v.trim();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try {
                return parseJson(v);
            } catch (Throwable ignored) {
            }
        }
        return v;
    }

    private static String mergeMethod(String optionsJson, String method) {
        try {
            Map<String, String> map = Json.toMap(optionsJson);
            if (map == null) map = new HashMap<>();
            map.put("method", method);
            return GSON.toJson(map);
        } catch (Throwable e) {
            return "{\"method\":\"" + method + "\"}";
        }
    }

    /**
     * 同步 fetch（跑在 JS 单线程上，直接阻塞等待）。
     * options 为 JSON：{headers, body/data, method, timeout, withHeaders}。
     */
    private String fetchSync(String url, String optionsJson) {
        if (TextUtils.isEmpty(url)) return "";
        // hiker://empty 等非 http 协议直接返回空（由上层按形态分流）
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        try {
            Req req = TextUtils.isEmpty(optionsJson) ? Req.objectFrom("{}") : Req.objectFrom(optionsJson);
            String opt = optionsJson == null ? "" : optionsJson;
            boolean withHeaders = opt.contains("\"withHeaders\":true") || opt.contains("\"withHeaders\":1");
            // P2：fetch 选项 withStatusCode / toHex / onlyHeaders
            boolean withStatusCode = opt.contains("\"withStatusCode\":true") || opt.contains("\"withStatusCode\":1");
            boolean toHex = opt.contains("\"toHex\":true") || opt.contains("\"toHex\":1");
            boolean onlyHeaders = opt.contains("\"onlyHeaders\":true") || opt.contains("\"onlyHeaders\":1");
            try (Response res = Connect.to(url, req).execute()) {
                resCode = res.code();
                lastUrl = url;
                Map<String, String> headers = new HashMap<>();
                for (String name : res.headers().names()) headers.put(name, res.header(name, ""));
                if (onlyHeaders) {
                    Map<String, Object> out = new HashMap<>();
                    out.put("headers", headers);
                    out.put("code", res.code());
                    return GSON.toJson(out);
                }
                ResponseBody body = res.body();
                byte[] bytes = body == null ? new byte[0] : body.bytes();
                String content;
                if (toHex) {
                    StringBuilder sb = new StringBuilder(bytes.length * 2);
                    for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
                    content = sb.toString();
                } else {
                    content = new String(bytes, req.getCharset());
                }
                if (withStatusCode) {
                    Map<String, Object> out = new HashMap<>();
                    out.put("body", content);
                    out.put("code", res.code());
                    out.put("headers", headers);
                    return GSON.toJson(out);
                }
                if (!withHeaders) return content;
                Map<String, Object> out = new HashMap<>();
                out.put("body", content);
                out.put("headers", headers);
                out.put("code", res.code());
                return GSON.toJson(out);
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("fetch failed %s: %s", url, e.getMessage());
            resCode = 0;
            return "";
        }
    }

    // ================= setItem/getItem 持久化 =================

    private File kvFile() {
        File dir = HkRuleManager.get().getDataDir(rule.getTitle());
        return new File(dir, "kv.json");
    }

    private void loadKv() {
        try {
            File f = kvFile();
            if (!f.exists()) return;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> map = GSON.fromJson(new String(bytes, "UTF-8"), type);
            if (map != null) kv.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private void saveKv() {
        try {
            File f = kvFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), GSON.toJson(kv).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }
}
