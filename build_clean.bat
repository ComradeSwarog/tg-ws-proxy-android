@echo off 
set " JAVA_HOME=C:\Program Files\Android\Android "Studio\jbr 
cd /d %%~dp0 
call gradlew.bat clean assembleDebug  
pause
