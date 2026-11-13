@echo off
set INTELLIJ_JAVA="C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.3\jbr\bin\"

echo Compiling...
if not exist src\Main.java (
    echo ❌ ERROR: Main.java not found in src\ folder!
    echo Move Main.java to src\ folder or run from root.
    pause
    exit /b 1
)

%INTELLIJ_JAVA%javac -encoding UTF-8 src\Main.java -d out
echo Running...
%INTELLIJ_JAVA%java -cp out Main