<#
  为容器模式准备运行时资源
  ------------------------------------------------------------------
  用法（在工程根目录 dualterm/ 下执行）：

      powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1
      powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1 -Abi armeabi-v7a
      powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1 -Abi x86_64   # 模拟器

  产物分两处，别搞混（这是 Android 10+ 的硬限制决定的）：

    A. jniLibs/<abi>/           —— 会被安装到 nativeLibraryDir，只有这里能 exec
         libproot.so            proot 本体（就是 proot 可执行文件改了个名）
         libproot_loader.so     proot 的 loader（绕过 rootfs 所在目录 noexec）
         libtalloc2.so          依赖库（NEEDED 已从 libtalloc.so.2 改写而来）
         libandroid-shmem.so    依赖库

    B. assets/runtime/
         rootfs-<abi>.pack      Alpine minirootfs（扩展名不能是 .tar.gz，AAPT 会改写）

  为什么不能像普通终端那样把 proot 放 filesDir：
    targetSdk >= 29 时，应用私有数据目录被强制 noexec（SELinux untrusted_app
    对 app_data_file 无 execute 权限），exec() 直接 EACCES；连 proot 去 exec
    rootfs 里的 /bin/sh 也会被拒，所以还要借 proot 自带的 loader 去 mmap+跳转。
    Termux 能直接在 $PREFIX 里跑，是因为它的 targetSdk 还停留在 28。

  依赖：Windows 10 1803+ 自带的 tar（bsdtar，可直接读 .deb 的 ar 格式）。
#>
param(
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',
    [string]$AlpineVersion = '3.20.3'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $repoRoot 'app\src\main\assets\runtime'
$jniLibs = Join-Path $repoRoot ('app\src\main\jniLibs\' + $Abi)
New-Item -ItemType Directory -Force -Path $assets | Out-Null
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null

switch ($Abi) {
    'arm64-v8a' { $alpineArch = 'aarch64'; $termuxArch = 'aarch64' }
    'armeabi-v7a' { $alpineArch = 'armv7'; $termuxArch = 'arm' }
    'x86_64' { $alpineArch = 'x86_64'; $termuxArch = 'x86_64' }
}

Write-Host ""
Write-Host "== DualTerm 运行时准备 ==" -ForegroundColor Cyan
Write-Host "ABI        : $Abi"
Write-Host "Alpine 架构: $alpineArch"
Write-Host "jniLibs    : $jniLibs"
Write-Host "assets     : $assets"
Write-Host ""

# ---------------------------------------------------------------- 1. rootfs
$rootfsName = "alpine-minirootfs-$AlpineVersion-$alpineArch.tar.gz"
$rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$alpineArch/$rootfsName"
$rootfsOut = Join-Path $assets "rootfs-$Abi.pack"

if (Test-Path $rootfsOut) {
    Write-Host "[跳过] 已存在 rootfs-$Abi.pack" -ForegroundColor DarkGray
}
else {
    Write-Host "[1/2] 下载根文件系统 $rootfsName ..."
    try {
        Invoke-WebRequest -Uri $rootfsUrl -OutFile $rootfsOut -UseBasicParsing
    }
    catch {
        throw "下载 rootfs 失败：$rootfsUrl`n$($_.Exception.Message)"
    }
    $size = [math]::Round((Get-Item $rootfsOut).Length / 1MB, 2)
    Write-Host "      OK  ($size MB) -> assets/runtime/rootfs-$Abi.pack" -ForegroundColor Green
}

# ---------------------------------------------------------------- 2. proot
$prootOut = Join-Path $jniLibs 'libproot.so'
if (Test-Path $prootOut) {
    Write-Host "[跳过] 已存在 jniLibs/$Abi/libproot.so" -ForegroundColor DarkGray
    return
}

$indexUrl = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-$termuxArch/Packages"
Write-Host "[2/2] 读取 Termux 软件源索引 ..."
$index = (Invoke-WebRequest -Uri $indexUrl -UseBasicParsing).Content
if ($index -is [byte[]]) {
    $index = [System.Text.Encoding]::UTF8.GetString($index)
}

$blocks = $index -split "`r?`n`r?`n"
$prootBlock = $blocks | Where-Object { $_ -match '(?m)^Package:\s*proot\s*$' } | Select-Object -First 1
if (-not $prootBlock) {
    throw "在 $indexUrl 里没找到 proot 包，请检查软件源地址是否变化"
}

$filename = ([regex]::Match($prootBlock, '(?m)^Filename:\s*(\S+)')).Groups[1].Value
$sha256 = ([regex]::Match($prootBlock, '(?m)^SHA256:\s*(\S+)')).Groups[1].Value
$debUrl = "https://packages.termux.dev/apt/termux-main/$filename"

$tmp = Join-Path $env:TEMP ("dualterm-proot-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$deb = Join-Path $tmp 'proot.deb'

try {
    Write-Host "      下载 $filename ..."
    Invoke-WebRequest -Uri $debUrl -OutFile $deb -UseBasicParsing

    if ($sha256) {
        $actual = (Get-FileHash -Algorithm SHA256 -Path $deb).Hash.ToLower()
        if ($actual -ne $sha256.ToLower()) {
            throw "SHA256 校验失败（期望 $sha256，实际 $actual）"
        }
        Write-Host "      SHA256 校验通过" -ForegroundColor Green
    }

    Write-Host "      解包 deb ..."
    & tar -xf $deb -C $tmp
    if ($LASTEXITCODE -ne 0) {
        throw "tar 无法解包 .deb，请用 7-Zip 手动解包（见脚本头部说明）"
    }
    $dataTar = Get-ChildItem -Path $tmp -Filter 'data.tar.*' | Select-Object -First 1
    if (-not $dataTar) {
        throw ".deb 里没找到 data.tar.*"
    }
    & tar -xf $dataTar.FullName -C $tmp
    if ($LASTEXITCODE -ne 0) {
        throw "tar 无法解包 $($dataTar.Name)"
    }

    $termuxUsr = Join-Path $tmp 'data\data\com.termux\files\usr'
    $prootSrc = Join-Path $termuxUsr 'bin\proot'
    if (-not (Test-Path $prootSrc)) {
        $prootSrc = (Get-ChildItem -Path $tmp -Recurse -Filter 'proot' -File |
                Where-Object { $_.DirectoryName -like '*bin*' } |
                Select-Object -First 1).FullName
    }
    if (-not $prootSrc -or -not (Test-Path $prootSrc)) {
        throw "deb 里没找到 proot 可执行文件"
    }

    Copy-Item $prootSrc $prootOut -Force

    # AGP 只打包匹配 lib*.so 的文件，libtalloc.so.2 会被静默丢弃。
    # 所以把 proot 的 DT_NEEDED 字符串原位改写成 libtalloc2.so（更短，NUL 补齐）。
    $bytes = [System.IO.File]::ReadAllBytes($prootOut)
    $oldBytes = [System.Text.Encoding]::ASCII.GetBytes("libtalloc.so.2`0")
    $newBytes = [System.Text.Encoding]::ASCII.GetBytes("libtalloc2.so`0`0")
    $pos = -1
    for ($i = 0; $i -le $bytes.Length - $oldBytes.Length; $i++) {
        $match = $true
        for ($j = 0; $j -lt $oldBytes.Length; $j++) {
            if ($bytes[$i + $j] -ne $oldBytes[$j]) { $match = $false; break }
        }
        if ($match) { $pos = $i; break }
    }
    if ($pos -lt 0) {
        throw "proot 里没找到 libtalloc.so.2 的 NEEDED 字符串，Termux 的打包方式可能变了"
    }
    for ($j = 0; $j -lt $newBytes.Length; $j++) { $bytes[$pos + $j] = $newBytes[$j] }
    [System.IO.File]::WriteAllBytes($prootOut, $bytes)
    Write-Host "      proot -> jniLibs/$Abi/libproot.so（NEEDED 已改写为 libtalloc2.so）" -ForegroundColor Green

    # loader：绕过 rootfs 所在数据目录的 noexec
    $loaderSrc = Join-Path $termuxUsr 'libexec\proot\loader'
    if (-not (Test-Path $loaderSrc)) {
        $loaderSrc = (Get-ChildItem -Path $tmp -Recurse -Filter 'loader' -File | Select-Object -First 1).FullName
    }
    if (-not $loaderSrc -or -not (Test-Path $loaderSrc)) {
        throw "deb 里没找到 proot 的 loader（没有它，noexec 目录里的 rootfs 无法启动）"
    }
    Copy-Item $loaderSrc (Join-Path $jniLibs 'libproot_loader.so') -Force
    Write-Host "      loader -> jniLibs/$Abi/libproot_loader.so" -ForegroundColor Green

    # 依赖库：同样必须进 jniLibs（数据目录 noexec 无法 dlopen）
    $libSrc = Join-Path $termuxUsr 'lib'
    if (Test-Path $libSrc) {
        Get-ChildItem -Path $libSrc -Recurse -File | Where-Object { $_.Name -like '*.so*' } | ForEach-Object {
            $target = switch ($_.Name) {
                'libtalloc.so.2' { 'libtalloc2.so' }
                default { $_.Name }
            }
            Copy-Item $_.FullName (Join-Path $jniLibs $target) -Force
            Write-Host "      $($_.Name) -> jniLibs/$Abi/$target" -ForegroundColor Green
        }
    }
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "完成。现在可以构建 APK：gradlew assembleDebug" -ForegroundColor Cyan
Write-Host "提示：jniLibs 与 assets 里的运行时二进制不要提交到公开仓库（见 .gitignore）。" -ForegroundColor DarkGray
