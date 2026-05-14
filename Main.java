import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class Main {

    // хранение статистики 1го процесса
    static class ProcessStat {
        long syscalls = 0;           // кол-во системных вызовов
        long fullQuanta = 0;         // квантов отработано целиком
        long interruptedQuanta = 0;  // квантов прервано syscalls
        long totalCpuNs = 0;          // общее время на CPU (user + syscall)
        long totalSyscallNs = 0;      // суммарное время внутри системных вызовов
        long lastSwitchIn = 0;        // время последнего переключения на CPU
        long lastSyscallEnter = 0;    // время входа в текущий системный вызов
        boolean onCpu = false;       // запущен ли процесс сейчас на CPU
        boolean inSyscall = false;   // флаг: внутри ли системного вызова
    }

    // Поиск строк с нужными полями, вытаскивание значений
    static final Pattern LINE_PATTERN = Pattern.compile(
            "\\{\\\"type\\\":\\\"(.*?)\\\",\\\"pid\\\":(\\d+),\\\"ts\\\":(\\d+)\\}"
    );

    public static void main(String[] args) throws Exception {
        // Проверка: нужно 3 аргумента (программа, файл, PID1, PID2)
        if (args.length < 3) {
            System.err.println("Использование: java Main <файл-out.txt> <PID1> <PID2>");
            System.err.println("Пример: java Main out.txt 822 820");
            return;
        }

        String fileName = args[0];
        int pid1 = Integer.parseInt(args[1]);
        int pid2 = Integer.parseInt(args[2]);

        // Сохранение статистики для каждого PID
        Map<Integer, ProcessStat> stats = new HashMap<>();

        // Чтение файла с данными от bpftrace
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(fileName), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // попытка распарсить строку
                Matcher matcher = LINE_PATTERN.matcher(line.trim());
                if (!matcher.matches()) {
                    continue; // если строка не подходит, то пропустить ее
                }

                // возможны типы: sys_enter, sys_exit, switch_in, switch_out
                String type = matcher.group(1);
                int pid = Integer.parseInt(matcher.group(2));
                long timestamp = Long.parseLong(matcher.group(3));

                //Подсчет только для двух нужных PID
                if (pid != pid1 && pid != pid2) {
                    continue;
                }

                // Создание статистики для этого PID
                ProcessStat stat = stats.computeIfAbsent(pid, k -> new ProcessStat());

                // Обработка события в зависимости от типа
                if (type.equals("sys_enter")) {          // начало системного вызова
                    stat.lastSyscallEnter = timestamp;
                    stat.inSyscall = true;
                    if (stat.onCpu) {
                        stat.interruptedQuanta++;       // квант прерван системным вызовом
                    }
                } else if (type.equals("sys_exit")) {    // завершение системного вызова
                    if (stat.inSyscall && stat.lastSyscallEnter > 0) {
                        long duration = timestamp - stat.lastSyscallEnter;
                        stat.totalSyscallNs += duration; // накопление времени в ядре
                        stat.syscalls++;
                    }
                    stat.inSyscall = false;
                } else if (type.equals("switch_in")) {    // процесс получил CPU
                    // подсчет времени, которое процесс провел вне CPU (для user-space)
                    if (stat.lastSwitchIn > 0) {
                        stat.totalCpuNs += Math.max(0, timestamp - stat.lastSwitchIn);
                    }
                    stat.onCpu = true;
                } else if (type.equals("switch_out")) {   // процесс покинул CPU
                    if (stat.onCpu) {
                        if (!stat.inSyscall) {
                            stat.fullQuanta++;           // квант отработан целиком
                        }
                        stat.lastSwitchIn = timestamp;    // запомнить для следующего включения
                    }
                    stat.onCpu = false;
                }
            }
        }

        //  заголовок таблицы
        System.out.println("pid,syscalls,full_quanta,interrupted_quanta,userspace_ms");

        //  Вывод статистики
        for (int pid : new int[]{pid1, pid2}) {
            ProcessStat stat = stats.get(pid);
            if (stat == null) {
                continue; // нет данных для этого PID
            }

            // чистое user-space время = общее время на CPU - время внутри системных вызовов
            long userspaceNs = Math.max(0, stat.totalCpuNs - stat.totalSyscallNs);
            // Конвертация наносекунд в миллисекунды
            double userspaceMs = userspaceNs / 1_000_000.0;

            System.out.printf(Locale.US, "%d,%d,%d,%d,%.3f%n",
                    pid,
                    stat.syscalls,
                    stat.fullQuanta,
                    stat.interruptedQuanta,
                    userspaceMs);
        }
    }
}
