# Linux
Профилирование нагрузки процессов с помощью bpftrace и Java

Запуск bpftrace:
sudo bpftrace -o out.txt trace.bt
Ctrl+C для прерывания.

Просмотр PID:
grep -o '"pid":[0-9]*' out.txt | sort -u

Запуск парсера:
java -cp out Main out.txt <PID1> <PID2>
