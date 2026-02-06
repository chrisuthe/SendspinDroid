@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
pushd "\\truenas\ChrisFiles\CodeProjects\SpinDroid\android"
call "%CD%\gradlew.bat" clean assembleDebug
popd
