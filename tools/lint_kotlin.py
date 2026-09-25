#!/usr/bin/env python3
#!/usr/bin/env python3
"""推送前的 Kotlin 静态检查（本项目专用，覆盖两类"只能等 CI 才发现"的错）。

用法：
    python3 tools/lint_kotlin.py app/src/main/java/**/*.kt        # 也可直接列目录里的文件
    python3 tools/lint_kotlin.py $(git diff --name-only HEAD~1 -- '*.kt')

检查两类：
  ① **扩展函数用了但没 import** —— launch / remember / collectAsState / withContext …
     不做 import 就是 Unresolved reference；
  ② **非 inline lambda 里的裸 return** —— withContext / launch / async / collect …
     这些 lambda 里不能写 `return xxx`（只能 return@withContext）。

为什么需要它：本项目在手机上开发，没有 Android SDK，编译只能等 CI（一轮约 3 分钟）。
这两类错已经各让 CI 白跑过若干轮，所以做成推送前 5 秒的本地检查。

退出码：有问题 1，干净 0（可直接串进脚本）。

为什么需要它：Kotlin 的扩展函数（launch/cancel/collectAsState/remember/getValue…）
不做 import 就是 Unresolved reference，而本机没有 Android SDK，只能等 CI 编译 ——
一轮 3 分钟。这个 lint 把这类错误在推送前就抓出来（已经栽过两次：
remember 一次、launch+cancel 一次）。
"""
import re, sys, os

# 扩展函数 → 正确的 import（同名包内声明不需要 import 的除外）
KNOWN = {
    "withContext": "kotlinx.coroutines.withContext",
    "withTimeoutOrNull": "kotlinx.coroutines.withTimeoutOrNull",
    "async": "kotlinx.coroutines.async",
    "awaitAll": "kotlinx.coroutines.awaitAll",
    "collectLatest": "kotlinx.coroutines.flow.collectLatest",
    "collectAsState": "androidx.compose.runtime.collectAsState",
    "collectAsStateWithLifecycle": "androidx.lifecycle.compose.collectAsStateWithLifecycle",
    "remember": "androidx.compose.runtime.remember",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
    "rememberLazyListState": "androidx.compose.foundation.lazy.rememberLazyListState",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "rememberDrawerState": "androidx.compose.material3.rememberDrawerState",
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "setViewTreeLifecycleOwner": "androidx.lifecycle.setViewTreeLifecycleOwner",
    "setViewTreeViewModelStoreOwner": "androidx.lifecycle.setViewTreeViewModelStoreOwner",
    "setViewTreeSavedStateRegistryOwner": "androidx.savedstate.setViewTreeSavedStateRegistryOwner",
    "viewModel": "androidx.lifecycle.viewmodel.compose.viewModel",
    "detectDragGestures": "androidx.compose.foundation.gestures.detectDragGestures",
    "pointerInput": "androidx.compose.ui.input.pointer.pointerInput",
    "clickable": "androidx.compose.foundation.clickable",
    "background": "androidx.compose.foundation.background",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
}

# 有歧义的：同名既有成员又有扩展。只在 receiver 明显是 scope 时才判定需要 import。
# 有歧义的：同名既有成员又有扩展，纯文本分不出来，只在 receiver 明显是 scope 时才判定需要 import。
#   · Job.cancel / CoroutineScope.cancel
#   · ManagedActivityResultLauncher.launch（pickImage.launch(...)）/
#     CoroutineScope.launch（uiScope.launch { }）—— 仓库里第一版把 4 个启动器
#     全误报成"缺 import"，就是这一类。
AMBIGUOUS = {
    "cancel": ("kotlinx.coroutines.cancel",
               re.compile(r"\b(\w*[Ss]cope\w*)\s*\.\s*cancel\s*\(")),
    "launch": ("kotlinx.coroutines.launch",
               re.compile(r"\b(\w*[Ss]cope\w*)\s*\.\s*launch\s*[({]")),
}

def strip_comments(src: str) -> str:
    """去掉 // 行注释与 /* */ 块注释（保留换行以维持行号）。

    为什么必须做：第一版没剥注释，仓库里的中文注释（"用 scope.launch { } 启动"）
    被当成真实调用，一次误报 5 处 —— 误报比不检查更糟，它教人忽略这个工具。
    """
    out = []
    in_block = False
    for line in src.split("\n"):
        res, i = [], 0
        while i < len(line):
            if in_block:
                j = line.find("*/", i)
                if j < 0:
                    i = len(line)
                else:
                    in_block = False
                    i = j + 2
                continue
            if line.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if line.startswith("//", i):
                break
            # 字符串字面量也跳掉：注释里/提示文案里写 "用 launch { } 启动" 不该算调用
            if line[i] == '"':
                i += 1
                while i < len(line):
                    if line[i] == '\\':
                        i += 2
                        continue
                    if line[i] == '"':
                        i += 1
                        break
                    i += 1
                continue
            res.append(line[i])
            i += 1
        out.append("".join(res))
    return "\n".join(out)


def check(path):
    s = strip_comments(open(path, encoding="utf-8").read())
    imports = set(re.findall(r"^import\s+([\w.*]+)", s, re.M))
    simple = {i.rsplit(".", 1)[-1]: i for i in imports}
    # 通配导入（import kotlinx.coroutines.*）同样能解析出 launch 等符号 ——
    # 不处理会造成误报（第一版就这样误报了 5 处，仓库其实编译得好好的）
    wildcards = {i[:-2] for i in imports if i.endswith(".*")}

    def covered(full):
        if simple.get(full.rsplit(".", 1)[-1]) == full:
            return True
        pkg = full.rsplit(".", 1)[0]
        return any(pkg == w or pkg.startswith(w + ".") for w in wildcards)
    # 本文件自己声明的函数名（含顶层与 object/class 内），不需要 import
    local = set(re.findall(r"\bfun\s+(?:<[^>]+>\s+)?(\w+)", s))
    problems = []
    for name, (full, rx) in AMBIGUOUS.items():
        if rx.search(s) and name not in local and not covered(full):
            problems.append((name, full))
    for name, full in KNOWN.items():
        # 只检查"以 receiver.name 形式被调用"或"直接调用"两种用法
        used = re.search(r"[\w\)\]]\s*\.\s*" + name + r"\s*[\({]|\b" + name + r"\s*[\({]", s)
        if not used: continue
        if name in local: continue
        if covered(full): continue
        # 允许全限定调用（个别场景）
        if "." + name + "(" in s and full in s: continue
        problems.append((name, full))
    return problems




# ---------------------------------------------------------------------------
# 第二类检查：非 inline lambda 里的裸 return
#
# 为什么需要：withContext / launch / async / collect 这些 lambda **不是 inline**，
# 里面写裸 `return xxx` 直接编译不过（" 'return' is prohibited here"）。
# 本机没有 Android SDK，只能等 CI 编译 —— 这条已经栽过一次（TrainingCollector）。
# ---------------------------------------------------------------------------
NON_INLINE_CALLS = ["withContext", "launch", "async", "collect", "withTimeout",
                    "coroutineScope", "supervisorScope", "flow"]


def check_bare_returns(path):
    src = strip_comments(open(path, encoding="utf-8").read())   # 注释里的 withContext 不算
    problems = []
    for call in NON_INLINE_CALLS:
        for m in re.finditer(r"\b" + call + r"\s*(\([^)]*\))?\s*\{", src):
            # 找到匹配的右花括号
            i = src.index("{", m.end() - 1)
            depth, j = 0, i
            while j < len(src):
                if src[j] == "{":
                    depth += 1
                elif src[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            body = src[i:j]
            # 逐行跟踪大括号深度，并记住"最近一个嵌套 fun 是在哪个深度开始的" ——
            # 嵌套函数体里的 return 是合法的（比如 WebSocketListener 的 override fun），
            # 不排除就会误报（EdgeTtsClient 第一版被误报 5 处）。
            depth = 0
            fun_depth = -1
            for line in body.split("\n"):
                stripped = line.strip()
                opens, closes = stripped.count("{"), stripped.count("}")
                if re.search(r"\bfun\s+\w+", stripped):
                    fun_depth = depth          # 这个 fun 的 body 从下一层开始
                if re.search(r"(?<![@\w])return\b(?!@)", stripped) and not (
                    fun_depth >= 0 and depth > fun_depth
                ):
                    problems.append((call, stripped[:80]))
                depth += opens - closes
                if fun_depth >= 0 and depth <= fun_depth:
                    fun_depth = -1             # 离开了这个嵌套函数
    return problems


def main():
    files = sys.argv[1:]
    bad_imports = 0
    bad_returns = 0
    for f in files:
        if not os.path.exists(f):
            continue
        for name, full in check(f):
            print(f"  ✗ {f}\n      用了 {name}() 但没有 import {full}")
            bad_imports += 1
        for call, line in check_bare_returns(f):
            print(f"  ✗ {f}\n      {call}{{}} 是非 inline lambda，里面不能裸 return：{line}")
            bad_returns += 1
    print(f"\n  检查 {len(files)} 个文件：缺 import {bad_imports} 处、裸 return {bad_returns} 处"
          + ("  ✅" if bad_imports + bad_returns == 0 else "  ⚠ 推送前先修"))
    sys.exit(1 if bad_imports + bad_returns else 0)


if __name__ == "__main__":
    main()
