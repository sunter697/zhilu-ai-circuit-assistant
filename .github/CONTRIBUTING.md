# Contributing

感谢你关注“智路：自然语言与视觉驱动的电路生成与编辑”。本仓库是在
[Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution) 基础上的衍生项目。

## 提交变更

1. 从 `main` 创建功能分支；
2. 只提交与本次变更相关的源码、测试和文档；
3. 不要提交 API 密钥、个人路径、训练缓存、虚拟环境或未说明来源的数据；
4. 新增第三方代码、模型或素材时，同步更新 `THIRD_PARTY_NOTICES.md`；
5. 在提交 Pull Request 前运行相应测试。

Windows：

```bat
gradlew.bat test checkstyleMain checkstyleTest
py -3.12 -m pip install -e ".\vision-service[test,model]"
py -3.12 -m pytest vision-service\tests -q
```

Linux/macOS：

```bash
./gradlew test checkstyleMain checkstyleTest
python3 -m pip install -e './vision-service[test,model]'
python3 -m pytest vision-service/tests -q
```

提交即表示你确认有权提供该贡献，并同意按照本仓库适用的开源许可证发布。对原始
Logisim-evolution 的通用修复，也可考虑向上游项目单独提交。
