#!/usr/bin/env python3
"""迷你 Maven 解析器：在**手机上**拼出编译用的 classpath（不需要 Android SDK / Gradle）。

为什么要它：本机没有 SDK，也没装 Gradle 的依赖缓存；而"能不能编译"过去只能等 CI（约 3 分钟）。
事实是类型检查只需要 jar —— framework 用 Robolectric 发的 android-all（等价 android.jar），
其余依赖按 POM 递归拉下来即可（d8/R8/apksigner 也都是纯 JVM，不构成障碍）。

用法：
    python3 tools/resolve_deps.py androidx.compose.material3:material3 androidx.room:room-runtime:2.6.1 ...
    # BOM（如 compose-bom）里的版本会自动用于未写版本的坐标

已知取舍：
  · 只按 POM 的 compile/runtime 依赖递归，不做 conflict resolution / 排除处理（够编译用）；
  · 串行下载（手机上并发收益有限），首次跑几分钟；
  · 依赖只落在 jars/ 与 poms/ 两个目录，可整目录删掉重来。
"""
import os, re, sys, urllib.request, xml.etree.ElementTree as ET

REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2"]
JARS, POMS = "jars", "poms"
CACHE = {}          # (g,a) -> version（BOM 管理）
done = set()

def fetch(url, dst):
    if os.path.exists(dst) and os.path.getsize(dst) > 0: return True
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=90) as r, open(dst, "wb") as f:
                f.write(r.read())
            return True
        except Exception:
            pass
    return False

def path_of(g, a, v):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"

def get_pom(g, a, v):
    local = f"{POMS}/{g}-{a}-{v}.pom"
    if not os.path.exists(local):
        for repo in REPOS:
            if fetch(f"{repo}/{path_of(g,a,v)}.pom", local): break
        else:
            open(local, "w").close()
    try:
        return ET.parse(local).getroot()
    except Exception:
        return None

def ns(tag):  # POM 有命名空间
    return tag.split('}')[-1]

def collect_bom(root):
    for el in root.iter():
        if ns(el.tag) != "dependency": continue
        d = {ns(c.tag): (c.text or "").strip() for c in el}
        if d.get("scope") == "import" and d.get("type") == "pom":
            r = get_pom(d["groupId"], d["artifactId"], d["version"])
            if r is not None: collect_bom(r)
        elif d.get("groupId") and d.get("artifactId") and d.get("version"):
            CACHE[(d["groupId"], d["artifactId"])] = d["version"]

def resolve(g, a, v=None, depth=0):
    if depth > 6: return
    v = v or CACHE.get((g, a))
    if not v or (g, a) in done: return
    done.add((g, a))
    root = get_pom(g, a, v)
    if root is None: return
    collect_bom(root)                      # POM 自己的 dependencyManagement 也收进来
    # 下载 jar（packaging=pom 的跳过）
    packaging = "jar"
    for el in root:
        if ns(el.tag) == "packaging": packaging = (el.text or "jar").strip()
    if packaging == "jar":
        jar = f"{JARS}/{a}-{v}.jar"
        if not os.path.exists(jar):
            for repo in REPOS:
                if fetch(f"{repo}/{path_of(g,a,v)}.jar", jar): break
    for el in root.iter():
        if ns(el.tag) != "dependencies": continue
        for dep in el:
            if ns(dep.tag) != "dependency": continue
            d = {ns(c.tag): (c.text or "").strip() for c in dep}
            if d.get("scope") in ("test", "provided", "system"): continue
            if d.get("optional") == "true": continue
            resolve(d.get("groupId"), d.get("artifactId"), d.get("version") or None, depth + 1)

if __name__ == "__main__":
    # BOM 先收版本
    for bom in ["androidx/compose:compose-bom:2024.09.02"]:
        g, a, v = bom.split(":")
        r = get_pom(g.replace("/", "."), a, v)
        if r is not None: collect_bom(r); print(f"  BOM {a} 已收 {len(CACHE)} 条版本")
    for spec in sys.argv[1:]:
        g, a, v = (spec.split(":") + [None])[:3]
        resolve(g, a, v)
    jars = [f for f in os.listdir(JARS) if f.endswith(".jar")]
    total = sum(os.path.getsize(os.path.join(JARS, f)) for f in jars)
    print(f"  已解析 {len(done)} 个坐标，落地 {len(jars)} 个 jar，合计 {total/1048576:.0f} MB")
