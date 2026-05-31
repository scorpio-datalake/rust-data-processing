# Pre-built libs for integration tests (see scripts/build_libs/)

Native libraries and env.sh files are produced locally. See `.gitignore`.

After building:

```bash
source integration_testing/libs/rust/env.sh
source integration_testing/libs/java/env.sh
source integration_testing/libs/python/env.sh
```

Build with:

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
```
