# 洗衣房材质

这两份源文件与 `assets/materials/` 中的二进制材质一同维护。应用直接读取预编译资源，启动时无需运行材质编译器。修改源文件或升级 Filament 后，使用与运行库版本一致的官方 `matc` 重新生成资源。

当前资源使用 [Filament v1.75.1 官方发布包](https://github.com/google/filament/releases/tag/v1.75.1) 中的 Windows `matc.exe` 编译，材质格式版本为 75。工具保存在仓库外；无需把工具或完整发布包提交到源码仓库。

在 Android 项目根目录执行（将路径替换为本机工具路径）：

```powershell
$matc = "C:/tools/filament-1.75.1/bin/matc.exe"
& $matc --platform mobile --api opengl --output app/src/main/assets/materials/room-surfaces.filamat app/src/main/materials/room-surfaces.mat
& $matc --platform mobile --api opengl --output app/src/main/assets/materials/room-lights.filamat app/src/main/materials/room-lights.mat
```

默认采用性能优化，与原先运行时编译设置一致。`room-surfaces` 使用顶点颜色，以及由代码传入的 `roughness`、`metallic`；`room-lights` 使用不受场景光照影响的顶点颜色。

当前 Windows 工具 SHA-256：`6c6a3ca34da85550c9a9675ace6e3e486f34f3f4b1c99a81902ed9791d0dbf1e`。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `room-surfaces.filamat` | 110517 | `aaad6137f58567d6dd1257953f11bd7d0b205dccce8c893b5d927eadc38a1df7` |
| `room-lights.filamat` | 18543 | `b51e1e0b6f175e8dfe06e515fb892d4cdec7fa0b6894bd67df5ecdb9e9c1a536` |
