# Git 版本管理

仓库：[berg-star/Ambient](https://github.com/berg-star/Ambient)。主分支为 `main`，远程名称为 `origin`。

## 当前基线

从当前 0.3.1 版开始建立 Git 历史。首个提交保存完整源码，`v0.3.1` 标签指向此版本。之前的开发记录保存在验证文档中，不能从 Git 还原尚未提交过的旧版源码。

源码、Gradle Wrapper、构建脚本、测试、验证文档和模拟器截图纳入版本管理。本机工具、缓存、模拟器、构建产物、安装包、本机路径配置及密钥通过 `.gitignore` 排除。

## 后续日常使用

在项目目录查看改动：

```powershell
git status
git diff
```

完成功能和必要验证后，记录并上传：

```powershell
git add .
git diff --cached --stat
git commit -m "简要说明本次改动"
git push
```

每次提交前检查暂存内容，尤其是新增的配置文件，勿把密钥写入源码或提交历史。提交保存本地版本，推送同步到 GitHub；只有提交而未推送的内容尚未备份到 GitHub。

未来发布下一版时，更新应用版本和 `CHANGELOG.md`，完成构建验证并提交后，再创建一个新的版本标签。已有标签不应移动到其他提交。查看已有标签：

```powershell
git tag --list
git log --oneline --decorate -10
```

若在另一台电脑或 GitHub 网页改过仓库，先在本地工作区干净时同步：

```powershell
git pull --ff-only
```

若提示分支发生分叉，应先查看差异、处理合并，不要使用强制推送覆盖远端。

## 换电脑

```powershell
git clone https://github.com/berg-star/Ambient.git
cd Ambient
```

按项目 README 配置 JDK 17 和 Android SDK，并复制 `toolchain.local.ps1.example` 为本机配置。SDK、缓存、密钥和 APK 不会随 Git 克隆；Debug APK 需重新构建。
