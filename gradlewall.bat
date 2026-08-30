@rem
@rem gradlewall — run gradlew.bat once per MC version node, serially, all nodes attempted.
@rem
@rem Every argument is passed straight through to gradlew.bat, so this is a
@rem drop-in alias:
@rem
@rem     gradlewall assemble
@rem     gradlewall build --console=plain
@rem     gradlewall clean
@rem
@rem becomes, for each node N under versions\:
@rem
@rem     gradlew.bat -PmcVersion=N <your args>
@rem
@rem A failing node does NOT stop the remaining ones; the run ends with a
@rem PASS/FAIL summary and exits 1 if any node failed.
@rem
@rem The node list is the set of directories under versions\ that carry a
@rem gradle.properties. It is never hardcoded here — versions\ is the single
@rem source of truth, exactly as settings.gradle.kts treats it. The
@rem versions\current FILE is skipped for free, since /d only matches directories.
@rem
@rem -PmcVersion outranks both MCP_MC_VERSION and versions\current in
@rem settings.gradle.kts, so this script never writes versions\current and leaves
@rem your active node untouched.
@rem
@echo off
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "PASSED="
set "FAILED="
set "STATUS=0"
set "FOUND=0"

for /d %%d in ("%DIR%versions\*") do (
    if exist "%%~fd\gradle.properties" (
        set "FOUND=1"
        echo(
        echo ============================================================
        echo   gradlewall: %%~nxd
        echo ============================================================

        rem `call` is required: without it, control never returns from gradlew.bat.
        call "%DIR%gradlew.bat" -PmcVersion=%%~nxd %*

        if errorlevel 1 (
            set "FAILED=!FAILED! %%~nxd"
            set "STATUS=1"
        ) else (
            set "PASSED=!PASSED! %%~nxd"
        )
    )
)

if "%FOUND%"=="0" (
    echo gradlewall: no version nodes found under %DIR%versions 1>&2
    endlocal & exit /b 1
)

echo(
echo ============================================================
echo   gradlewall summary
echo ============================================================
for %%v in (%PASSED%) do echo   PASS  %%v
for %%v in (%FAILED%) do echo   FAIL  %%v

endlocal & exit /b %STATUS%
