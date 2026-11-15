import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiniDB {
    private static final String DATABASE_FILE = "database.dat";
    private static final String INDEX_FILE = "indexes.dat";

    enum DataType {
        INT, DOUBLE, STRING, DATE, BOOLEAN
    }

    static class Column implements Serializable {
        String name;
        DataType type;
        boolean primaryKey;
        String refTable;
        String refColumn;

        Column(String name, DataType type) {
            this.name = name;
            this.type = type;
        }
    }

    static class Table implements Serializable {
        String name;
        List<Column> columns = new ArrayList<Column>();
        LinkedHashMap<Object, LinkedHashMap<String, Object>> rows = new LinkedHashMap<Object, LinkedHashMap<String, Object>>();
        List<String> indexedColumns = new ArrayList<String>();

        Table(String name) {
            this.name = name.toLowerCase();
        }

        Column getColumn(String columnName) {
            for (Column column : columns) {
                if (column.name.equalsIgnoreCase(columnName)) {
                    return column;
                }
            }
            return null;
        }

        Column getPrimaryKey() {
            for (Column column : columns) {
                if (column.primaryKey) {
                    return column;
                }
            }
            return null;
        }
    }

    static class DatabaseData implements Serializable {
        LinkedHashMap<String, Table> tables = new LinkedHashMap<String, Table>();
    }

    static class IndexData implements Serializable {
        LinkedHashMap<String, LinkedHashMap<String, TreeMap<Object, LinkedHashSet<Object>>>> indexes =
                new LinkedHashMap<String, LinkedHashMap<String, TreeMap<Object, LinkedHashSet<Object>>>>();
    }

    static class Condition {
        String column;
        String operator;
        Object value;

        Condition(String column, String operator, Object value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }

    private DatabaseData database;
    private IndexData indexData;

    public MiniDB() throws Exception {
        database = loadObject(DATABASE_FILE, DatabaseData.class);
        if (database == null) {
            database = new DatabaseData();
        }

        indexData = loadObject(INDEX_FILE, IndexData.class);
        if (indexData == null) {
            indexData = new IndexData();
            rebuildAllIndexes();
            saveIndexes();
        } else {
            makeSureIndexesExist();
        }
    }

    public static void main(String[] args) {
        try {
            MiniDB db = new MiniDB();
            Scanner scanner = new Scanner(System.in);

            System.out.println("Упрощенная СУБД на Java");
            System.out.println("Введите SQL-команду или exit для выхода.");
            System.out.println("Пример: SELECT * FROM cars;");

            while (true) {
                System.out.print("sql> ");
                if (!scanner.hasNextLine()) {
                    break;
                }

                String command = scanner.nextLine().trim();
                if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
                    break;
                }
                if (command.length() == 0) {
                    continue;
                }

                try {
                    db.execute(command);
                } catch (Exception e) {
                    System.out.println("Ошибка: " + e.getMessage());
                }
            }

            scanner.close();
            System.out.println("Работа программы завершена.");
        } catch (Exception e) {
            System.out.println("Не удалось запустить базу данных: " + e.getMessage());
        }
    }

    private void execute(String sql) throws Exception {
        String command = removeSemicolon(sql.trim());
        String upper = command.toUpperCase();

        if (upper.startsWith("CREATE TABLE ")) {
            createTable(command);
        } else if (upper.startsWith("DROP TABLE ")) {
            dropTable(command);
        } else if (upper.startsWith("CREATE INDEX ")) {
            createIndex(command);
        } else if (upper.startsWith("INSERT INTO ")) {
            insert(command);
        } else if (upper.startsWith("SELECT ")) {
            select(command);
        } else if (upper.startsWith("UPDATE ")) {
            update(command);
        } else if (upper.startsWith("DELETE FROM ")) {
            delete(command);
        } else {
            throw new IllegalArgumentException("Неизвестная команда.");
        }
    }

    private void createTable(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Формат: CREATE TABLE table_name (id INT PRIMARY KEY, name STRING)");
        }

        String tableName = matcher.group(1).toLowerCase();
        if (database.tables.containsKey(tableName)) {
            throw new IllegalArgumentException("Таблица уже существует: " + tableName);
        }

        Table table = new Table(tableName);
        List<String> definitions = splitTopLevel(matcher.group(2));
        for (String definition : definitions) {
            Matcher columnMatcher = Pattern.compile(
                    "(?is)^(\\w+)\\s+(INT|INTEGER|DOUBLE|FLOAT|REAL|STRING|TEXT|VARCHAR(?:\\s*\\(\\d+\\))?|DATE|BOOLEAN|BOOL)(.*)$")
                    .matcher(definition.trim());
            if (!columnMatcher.matches()) {
                throw new IllegalArgumentException("Не удалось разобрать описание столбца: " + definition);
            }

            String columnName = columnMatcher.group(1).toLowerCase();
            if (table.getColumn(columnName) != null) {
                throw new IllegalArgumentException("Повторяется имя столбца: " + columnName);
            }

            Column column = new Column(columnName, parseType(columnMatcher.group(2)));
            String options = columnMatcher.group(3);

            if (options.toUpperCase().contains("PRIMARY KEY")) {
                if (table.getPrimaryKey() != null) {
                    throw new IllegalArgumentException("В таблице должен быть один первичный ключ.");
                }
                column.primaryKey = true;
            }

            Matcher refMatcher = Pattern.compile("(?i)REFERENCES\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)").matcher(options);
            if (refMatcher.find()) {
                column.refTable = refMatcher.group(1).toLowerCase();
                column.refColumn = refMatcher.group(2).toLowerCase();
            }
            table.columns.add(column);
        }

        if (table.columns.size() == 0 || table.getPrimaryKey() == null) {
            throw new IllegalArgumentException("В каждой таблице должен быть первичный ключ.");
        }

        for (Column column : table.columns) {
            if (column.refTable != null) {
                Table parent = getTable(column.refTable);
                Column parentColumn = parent.getColumn(column.refColumn);
                if (parentColumn == null || !parentColumn.primaryKey) {
                    throw new IllegalArgumentException("Внешний ключ должен ссылаться на существующий первичный ключ.");
                }
            }
        }

        database.tables.put(tableName, table);
        saveDatabase();
        rebuildAllIndexes();
        saveIndexes();
        System.out.println("Таблица " + tableName + " создана.");
    }

    private void dropTable(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?i)^DROP\\s+TABLE\\s+(\\w+)$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Формат: DROP TABLE table_name");
        }

        String tableName = matcher.group(1).toLowerCase();
        Table table = getTable(tableName);

        for (Table other : database.tables.values()) {
            for (Column column : other.columns) {
                if (tableName.equals(column.refTable)) {
                    throw new IllegalArgumentException("Нельзя удалить таблицу: на нее ссылается " + other.name);
                }
            }
        }

        database.tables.remove(tableName);
        indexData.indexes.remove(tableName);
        saveDatabase();
        saveIndexes();
        System.out.println("Таблица " + tableName + " удалена.");
    }

    private void createIndex(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?i)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$")
                .matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Формат: CREATE INDEX index_name ON table_name (column_name)");
        }

        String tableName = matcher.group(2).toLowerCase();
        String columnName = matcher.group(3).toLowerCase();
        Table table = getTable(tableName);
        if (table.getColumn(columnName) == null) {
            throw new IllegalArgumentException("Такого столбца нет: " + columnName);
        }
        if (!table.indexedColumns.contains(columnName)) {
            table.indexedColumns.add(columnName);
        }

        rebuildIndex(table, columnName);
        saveDatabase();
        saveIndexes();
        System.out.println("Индекс создан для " + tableName + "." + columnName + ".");
    }

    private void insert(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)(?:\\s*\\((.*?)\\))?\\s+VALUES\\s*(.+)$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Проверьте формат INSERT INTO.");
        }

        Table table = getTable(matcher.group(1));
        List<String> columnNames = new ArrayList<String>();

        if (matcher.group(2) == null) {
            for (Column column : table.columns) {
                columnNames.add(column.name);
            }
        } else {
            for (String name : splitTopLevel(matcher.group(2))) {
                String columnName = name.trim().toLowerCase();
                if (table.getColumn(columnName) == null) {
                    throw new IllegalArgumentException("Неизвестный столбец: " + columnName);
                }
                if (columnNames.contains(columnName)) {
                    throw new IllegalArgumentException("Столбец указан дважды: " + columnName);
                }
                columnNames.add(columnName);
            }
        }

        List<String> tuples = readTuples(matcher.group(3).trim());
        if (tuples.size() == 0) {
            throw new IllegalArgumentException("Не найдены значения для вставки.");
        }

        int inserted = 0;
        for (String tuple : tuples) {
            List<String> values = splitTopLevel(tuple);
            if (values.size() != columnNames.size()) {
                throw new IllegalArgumentException("Количество значений не совпадает с количеством столбцов.");
            }

            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            for (Column column : table.columns) {
                row.put(column.name, null);
            }

            for (int i = 0; i < columnNames.size(); i++) {
                Column column = table.getColumn(columnNames.get(i));
                row.put(column.name, parseValue(values.get(i), column.type));
            }

            Object key = row.get(table.getPrimaryKey().name);
            if (key == null) {
                throw new IllegalArgumentException("Не задан первичный ключ: " + table.getPrimaryKey().name);
            }
            if (table.rows.containsKey(key)) {
                throw new IllegalArgumentException("Первичный ключ уже существует: " + key);
            }

            validateForeignKeys(table, row);
            table.rows.put(key, row);
            inserted++;
        }

        rebuildAllIndexes();
        saveDatabase();
        saveIndexes();
        System.out.println("Добавлено записей: " + inserted);
    }

    private void select(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?is)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Проверьте формат SELECT.");
        }

        String selectedText = matcher.group(1).trim();
        Table table = getTable(matcher.group(2));
        Condition condition = null;
        if (matcher.group(3) != null) {
            condition = parseCondition(table, matcher.group(3).trim());
        }

        List<String> selectedColumns = new ArrayList<String>();
        if (selectedText.equals("*")) {
            for (Column column : table.columns) {
                selectedColumns.add(column.name);
            }
        } else {
            for (String name : splitTopLevel(selectedText)) {
                String columnName = name.trim().toLowerCase();
                if (table.getColumn(columnName) == null) {
                    throw new IllegalArgumentException("Неизвестный столбец: " + columnName);
                }
                selectedColumns.add(columnName);
            }
        }

        long start = System.nanoTime();
        List<LinkedHashMap<String, Object>> result = findRows(table, condition);
        long time = System.nanoTime() - start;

        printTable(selectedColumns, result);
        if (condition != null && table.indexedColumns.contains(condition.column)) {
            System.out.println("Способ поиска: индекс по столбцу " + condition.column);
        } else {
            System.out.println("Способ поиска: полный просмотр таблицы");
        }
        System.out.println("Найдено записей: " + result.size() + ". Время поиска: " + (time / 1000000.0) + " мс.");
    }

    private void update(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Проверьте формат UPDATE.");
        }

        Table table = getTable(matcher.group(1));
        List<String> assignments = splitTopLevel(matcher.group(2));
        LinkedHashMap<String, Object> newValues = new LinkedHashMap<String, Object>();

        for (String assignment : assignments) {
            Matcher setMatcher = Pattern.compile("(?is)^\\s*(\\w+)\\s*=\\s*(.+?)\\s*$").matcher(assignment);
            if (!setMatcher.matches()) {
                throw new IllegalArgumentException("Ошибка в SET: " + assignment);
            }
            String columnName = setMatcher.group(1).toLowerCase();
            Column column = table.getColumn(columnName);
            if (column == null) {
                throw new IllegalArgumentException("Неизвестный столбец: " + columnName);
            }
            if (column.primaryKey) {
                throw new IllegalArgumentException("Изменение первичного ключа в этой версии не поддерживается.");
            }
            newValues.put(columnName, parseValue(setMatcher.group(2), column.type));
        }

        Condition condition = null;
        if (matcher.group(3) != null) {
            condition = parseCondition(table, matcher.group(3).trim());
        }

        List<Object> keys = new ArrayList<Object>(table.rows.keySet());
        int updated = 0;
        for (Object key : keys) {
            LinkedHashMap<String, Object> row = table.rows.get(key);
            if (condition == null || matches(row, condition)) {
                LinkedHashMap<String, Object> changedRow = new LinkedHashMap<String, Object>(row);
                for (Map.Entry<String, Object> entry : newValues.entrySet()) {
                    changedRow.put(entry.getKey(), entry.getValue());
                }
                validateForeignKeys(table, changedRow);
                table.rows.put(key, changedRow);
                updated++;
            }
        }

        rebuildAllIndexes();
        saveDatabase();
        saveIndexes();
        System.out.println("Обновлено записей: " + updated);
    }

    private void delete(String sql) throws Exception {
        Matcher matcher = Pattern.compile("(?is)^DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Проверьте формат DELETE.");
        }

        Table table = getTable(matcher.group(1));
        Condition condition = null;
        if (matcher.group(2) != null) {
            condition = parseCondition(table, matcher.group(2).trim());
        }

        List<Object> keysToDelete = new ArrayList<Object>();
        for (Map.Entry<Object, LinkedHashMap<String, Object>> entry : table.rows.entrySet()) {
            if (condition == null || matches(entry.getValue(), condition)) {
                keysToDelete.add(entry.getKey());
            }
        }

        for (Object key : keysToDelete) {
            if (hasReferences(table.name, key)) {
                throw new IllegalArgumentException("Нельзя удалить запись " + key + ": на нее ссылается другая таблица.");
            }
        }

        for (Object key : keysToDelete) {
            table.rows.remove(key);
        }

        rebuildAllIndexes();
        saveDatabase();
        saveIndexes();
        System.out.println("Удалено записей: " + keysToDelete.size());
    }

    private Condition parseCondition(Table table, String text) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(\\w+)\\s*(>=|<=|<>|!=|=|>|<)\\s*(.+?)\\s*$").matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Условие должно иметь вид: столбец = значение");
        }

        String columnName = matcher.group(1).toLowerCase();
        Column column = table.getColumn(columnName);
        if (column == null) {
            throw new IllegalArgumentException("Неизвестный столбец в условии: " + columnName);
        }

        Object value = parseValue(matcher.group(3), column.type);
        return new Condition(columnName, matcher.group(2), value);
    }

    private List<LinkedHashMap<String, Object>> findRows(Table table, Condition condition) {
        List<LinkedHashMap<String, Object>> result = new ArrayList<LinkedHashMap<String, Object>>();

        if (condition != null && table.indexedColumns.contains(condition.column)) {
            TreeMap<Object, LinkedHashSet<Object>> index = getIndex(table.name, condition.column);
            if (index != null) {
                Set<Object> keys = findKeysInIndex(index, condition);
                for (Object key : keys) {
                    LinkedHashMap<String, Object> row = table.rows.get(key);
                    if (row != null && matches(row, condition)) {
                        result.add(row);
                    }
                }
                return result;
            }
        }

        for (LinkedHashMap<String, Object> row : table.rows.values()) {
            if (condition == null || matches(row, condition)) {
                result.add(row);
            }
        }
        return result;
    }

    private Set<Object> findKeysInIndex(TreeMap<Object, LinkedHashSet<Object>> index, Condition condition) {
        LinkedHashSet<Object> result = new LinkedHashSet<Object>();
        Object value = condition.value;

        if (condition.operator.equals("=")) {
            Set<Object> found = index.get(value);
            if (found != null) {
                result.addAll(found);
            }
        } else if (condition.operator.equals(">")) {
            addIndexValues(result, index.tailMap(value, false));
        } else if (condition.operator.equals(">=")) {
            addIndexValues(result, index.tailMap(value, true));
        } else if (condition.operator.equals("<")) {
            addIndexValues(result, index.headMap(value, false));
        } else if (condition.operator.equals("<=")) {
            addIndexValues(result, index.headMap(value, true));
        } else {
            addIndexValues(result, index);
            Set<Object> equal = index.get(value);
            if (equal != null) {
                result.removeAll(equal);
            }
        }
        return result;
    }

    private void addIndexValues(Set<Object> result, Map<Object, LinkedHashSet<Object>> values) {
        for (Set<Object> keys : values.values()) {
            result.addAll(keys);
        }
    }

    private boolean matches(Map<String, Object> row, Condition condition) {
        Object left = row.get(condition.column);
        Object right = condition.value;
        if (left == null || right == null) {
            return false;
        }

        int comparison = compareValues(left, right);
        if (condition.operator.equals("=")) {
            return comparison == 0;
        }
        if (condition.operator.equals("!=") || condition.operator.equals("<>")) {
            return comparison != 0;
        }
        if (condition.operator.equals(">")) {
            return comparison > 0;
        }
        if (condition.operator.equals("<")) {
            return comparison < 0;
        }
        if (condition.operator.equals(">=")) {
            return comparison >= 0;
        }
        if (condition.operator.equals("<=")) {
            return comparison <= 0;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private int compareValues(Object first, Object second) {
        if (first instanceof Number && second instanceof Number) {
            return Double.compare(((Number) first).doubleValue(), ((Number) second).doubleValue());
        }
        return ((Comparable<Object>) first).compareTo(second);
    }

    private void validateForeignKeys(Table table, Map<String, Object> row) {
        for (Column column : table.columns) {
            if (column.refTable != null) {
                Object value = row.get(column.name);
                if (value == null) {
                    continue;
                }
                Table parent = getTable(column.refTable);
                if (!parent.rows.containsKey(value)) {
                    throw new IllegalArgumentException("Для " + column.name + " не найдена запись " + value + " в таблице " + parent.name);
                }
            }
        }
    }

    private boolean hasReferences(String tableName, Object key) {
        for (Table table : database.tables.values()) {
            for (Column column : table.columns) {
                if (tableName.equals(column.refTable)) {
                    for (Map<String, Object> row : table.rows.values()) {
                        if (key.equals(row.get(column.name))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void rebuildAllIndexes() {
        indexData = new IndexData();
        for (Table table : database.tables.values()) {
            for (String columnName : table.indexedColumns) {
                rebuildIndex(table, columnName);
            }
        }
    }

    private void rebuildIndex(Table table, String columnName) {
        LinkedHashMap<String, TreeMap<Object, LinkedHashSet<Object>>> tableIndexes = indexData.indexes.get(table.name);
        if (tableIndexes == null) {
            tableIndexes = new LinkedHashMap<String, TreeMap<Object, LinkedHashSet<Object>>>();
            indexData.indexes.put(table.name, tableIndexes);
        }

        TreeMap<Object, LinkedHashSet<Object>> index = new TreeMap<Object, LinkedHashSet<Object>>();
        String primaryKeyName = table.getPrimaryKey().name;

        for (Map<String, Object> row : table.rows.values()) {
            Object value = row.get(columnName);
            Object primaryKey = row.get(primaryKeyName);
            if (value != null) {
                LinkedHashSet<Object> keys = index.get(value);
                if (keys == null) {
                    keys = new LinkedHashSet<Object>();
                    index.put(value, keys);
                }
                keys.add(primaryKey);
            }
        }
        tableIndexes.put(columnName, index);
    }

    private TreeMap<Object, LinkedHashSet<Object>> getIndex(String tableName, String columnName) {
        Map<String, TreeMap<Object, LinkedHashSet<Object>>> tableIndexes = indexData.indexes.get(tableName);
        if (tableIndexes == null) {
            return null;
        }
        return tableIndexes.get(columnName);
    }

    private void makeSureIndexesExist() throws Exception {
        boolean changed = false;
        for (Table table : database.tables.values()) {
            for (String columnName : table.indexedColumns) {
                if (getIndex(table.name, columnName) == null) {
                    rebuildIndex(table, columnName);
                    changed = true;
                }
            }
        }
        if (changed) {
            saveIndexes();
        }
    }

    private void printTable(List<String> columnNames, List<LinkedHashMap<String, Object>> rows) {
        List<Integer> widths = new ArrayList<Integer>();
        for (String columnName : columnNames) {
            widths.add(Math.max(columnName.length(), 4));
        }

        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columnNames.size(); i++) {
                String value = valueToString(row.get(columnNames.get(i)));
                widths.set(i, Math.min(25, Math.max(widths.get(i), value.length())));
            }
        }

        printSeparator(widths);
        for (int i = 0; i < columnNames.size(); i++) {
            System.out.print("| " + fit(columnNames.get(i), widths.get(i)) + " ");
        }
        System.out.println("|");
        printSeparator(widths);

        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columnNames.size(); i++) {
                System.out.print("| " + fit(valueToString(row.get(columnNames.get(i))), widths.get(i)) + " ");
            }
            System.out.println("|");
        }
        printSeparator(widths);
    }

    private void printSeparator(List<Integer> widths) {
        for (Integer width : widths) {
            System.out.print("+" + repeat("-", width + 2));
        }
        System.out.println("+");
    }

    private String fit(String value, int width) {
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + repeat(" ", width - value.length());
    }

    private String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(text);
        }
        return result.toString();
    }

    private String valueToString(Object value) {
        if (value == null) {
            return "NULL";
        }
        return String.valueOf(value);
    }

    private Table getTable(String tableName) {
        Table table = database.tables.get(tableName.toLowerCase());
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        return table;
    }

    private DataType parseType(String typeName) {
        String type = typeName.toUpperCase().replaceAll("\\s*\\(.*\\)", "");
        if (type.equals("INT") || type.equals("INTEGER")) {
            return DataType.INT;
        }
        if (type.equals("DOUBLE") || type.equals("FLOAT") || type.equals("REAL")) {
            return DataType.DOUBLE;
        }
        if (type.equals("DATE")) {
            return DataType.DATE;
        }
        if (type.equals("BOOLEAN") || type.equals("BOOL")) {
            return DataType.BOOLEAN;
        }
        return DataType.STRING;
    }

    private Object parseValue(String text, DataType type) {
        String value = text.trim();
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }

        try {
            if (type == DataType.INT) {
                return Integer.parseInt(value);
            }
            if (type == DataType.DOUBLE) {
                return Double.parseDouble(value);
            }
            if (type == DataType.BOOLEAN) {
                if (!value.equalsIgnoreCase("TRUE") && !value.equalsIgnoreCase("FALSE")) {
                    throw new IllegalArgumentException("Логическое значение должно быть TRUE или FALSE.");
                }
                return Boolean.parseBoolean(value);
            }
            if (type == DataType.DATE) {
                return LocalDate.parse(removeQuotes(value));
            }
            return removeQuotes(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Неверное значение: " + value);
        }
    }

    private String removeQuotes(String value) {
        String result = value.trim();
        if (result.length() >= 2 && result.charAt(0) == '\'' && result.charAt(result.length() - 1) == '\'') {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("''", "'");
    }

    private List<String> splitTopLevel(String text) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int depth = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    current.append(ch);
                    current.append(text.charAt(i + 1));
                    i++;
                    continue;
                }
                inQuotes = !inQuotes;
                current.append(ch);
            } else if (!inQuotes && ch == '(') {
                depth++;
                current.append(ch);
            } else if (!inQuotes && ch == ')') {
                depth--;
                current.append(ch);
            } else if (!inQuotes && depth == 0 && ch == ',') {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private List<String> readTuples(String text) {
        List<String> result = new ArrayList<String>();
        boolean inQuotes = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inQuotes = !inQuotes;
            } else if (!inQuotes && ch == '(') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (!inQuotes && ch == ')') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(text.substring(start, i));
                    start = -1;
                }
                if (depth < 0) {
                    throw new IllegalArgumentException("Неправильные скобки в VALUES.");
                }
            }
        }

        if (depth != 0 || inQuotes) {
            throw new IllegalArgumentException("Не закрыты скобки или кавычки в VALUES.");
        }
        return result;
    }

    private String removeSemicolon(String text) {
        String result = text.trim();
        if (result.endsWith(";")) {
            return result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private <T> T loadObject(String fileName, Class<T> objectClass) throws Exception {
        Path path = Paths.get(fileName);
        if (!Files.exists(path)) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(fileName))) {
            Object object = input.readObject();
            return objectClass.cast(object);
        }
    }

    private void saveDatabase() throws Exception {
        saveObject(DATABASE_FILE, database);
    }

    private void saveIndexes() throws Exception {
        saveObject(INDEX_FILE, indexData);
    }

    private void saveObject(String fileName, Object object) throws Exception {
        Path target = Paths.get(fileName);
        Path temporary = Paths.get(fileName + ".tmp");

        try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(temporary.toFile()))) {
            output.writeObject(object);
        }

        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
