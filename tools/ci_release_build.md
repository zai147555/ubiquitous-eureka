# CI 加一个「release 包」需要改的 workflow（我改不了，需要带 workflow 权限的 token）

## 为什么值得做

用户测的是 **debug 包**（99MB、`debuggable`、无 R8、无 AOT profile）。
安卓上 debug 与 release 的**流畅度差距是成倍的** —— 界面优化的收益会被 debug 开销盖掉。
`app/build.gradle.kts` 里 release **已经配好** R8 压缩 + 资源压缩 + 规则文件，
并且已改为用 debug 签名（`signingConfig = signingConfigs.getByName("debug")`），
所以 `./gradlew assembleRelease` 现在就能产出**可安装**的优化包。

## 需要在 `.github/workflows/*.yml` 里加的部分

```yaml
      # 1) 在原有的 assembleDebug 之后，多编一个 release
      - name: Assemble release APK
        run: ./gradlew :app:assembleRelease --build-cache

      # 2) 单独上传（debug 包那条保持不动）
      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: nekonyan-release-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
```

## 可选的进一步提速（收益从高到低）

```yaml
      # a) ccache 跨次缓存：build.gradle.kts 已声明 launcher，只需缓存目录
      - uses: actions/cache@v4
        with:
          path: ~/.cache/ccache
          key: ccache-${{ runner.os }}-${{ hashFiles('app/src/main/cpp/**') }}
          restore-keys: ccache-${{ runner.os }}-
        env:
          CCACHE_DIR: ~/.cache/ccache
          CCACHE_COMPILERCHECK: content

      # b) 模型与 ncnn 预编译包缓存（脚本本身幂等，只是每次 runner 都是空的）
      - uses: actions/cache@v4
        with:
          path: |
            app/src/main/assets/models/v1
            app/src/main/cpp/ncnn
          key: deps-${{ runner.os }}-${{ hashFiles('tools/fetch_*.sh') }}

      # c) 只跑 debug 的 lint 会省时间，但**别去掉单测**：
      #    单测是目前唯一能抓真 bug 的闸门（外键、汉明距离、迁移策略都是它抓的）
      - run: ./gradlew :app:assembleDebug -x lint
```

> 注意 (b)：那两个目录在**仓库树里**（不是 `build/`），所以 `actions/setup-gradle` 的缓存
> 默认盖不到它们 —— 必须像上面那样显式声明 `actions/cache`。
