/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet.main.messages;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.DXNetMain;
import de.hhu.bsinfo.dxutils.serialization.ObjectSizeUtil;

/**
 * Utility class for creating message classes at runtime.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 06.09.18
 */
public final class DynamicMessageCreator {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNetMain.class.getSimpleName());

    private static final long SEED;
    private static final int MAX_ATTRIBUTES = 65_535; // This is the attribute limit from Java
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE;
    private static final int MAX_STRING_LENGTH = Integer.MAX_VALUE;

    private static final File ROOT;
    private static final File SOURCE_FILE;
    private static final String PACKAGE;
    private static final String CLASS_NAME;

    private static final String HEADER;
    private static final String FOOTER;

    static {
        SEED = System.currentTimeMillis();

        ROOT = new File("de/hhu/bsinfo/dxnet/main/messages");
        if (!ROOT.mkdirs()) {
            LOGGER.error("Root folder could not be created");
        }
        SOURCE_FILE = new File(ROOT, "DynamicMessage.java");
        PACKAGE = "de.hhu.bsinfo.dxnet.main.messages";
        CLASS_NAME = "DynamicMessage";

        HEADER = "package " + PACKAGE + ";\n\n" + "import java.util.Arrays;\n" +
                "import de.hhu.bsinfo.dxnet.core.AbstractMessageExporter;\n" +
                "import de.hhu.bsinfo.dxnet.core.AbstractMessageImporter;\n" +
                "import de.hhu.bsinfo.dxnet.core.Message;\n\n" + "public class " + CLASS_NAME +
                " extends Message {\n\n";
        FOOTER = "}\n";
    }

    /**
     * Utility class
     */
    private DynamicMessageCreator() {
    }

    public static void cleanup() {
        try {
            Files.walk(Paths.get("de")).map(Path::toFile).sorted((o1, o2) -> -o1.compareTo(o2)).forEach(File::delete);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Generates a set of attributes which will be inserted in the new message class.
     * The number of attributes and its sizes are random.
     *
     * @param p_destination
     *         the destination
     * @return an array with initialized attributes
     */
    public static Object[] createWorkload(final short p_destination) {
        Random rand = new Random(SEED);

        int numberOfAttributes = rand.nextInt(MAX_ATTRIBUTES);
        ArrayList<Object> attributes = new ArrayList<>(numberOfAttributes);
        attributes.add(p_destination);

        for (int i = 0; i < numberOfAttributes; i++) {
            generateAttribute(attributes, rand);
        }

        return attributes.toArray();
    }

    /**
     * Generates a set of attributes which will be inserted in the new message class.
     * The number of attributes and its sizes are random but the serialized length is exactly p_size.
     *
     * @param p_destination
     *         the destination
     * @param p_size
     *         the size of the serialized message
     * @return an array with initialized attributes
     */
    public static Object[] createWorkload(final short p_destination, final int p_size) {
        Random rand = new Random(SEED);

        ArrayList<Object> attributes = new ArrayList<>();
        attributes.add(p_destination);

        int i = 0;
        int length = 0;
        while (length < p_size && i < MAX_ATTRIBUTES) {
            length += generateAttribute(attributes, rand, p_size - length);
            i++;
        }

        if (length < p_size) {
            LOGGER.warn(
                    "Message is smaller (%d bytes < %d bytes) because number of maximum attributes (%d) was reached.",
                    length, p_size, MAX_ATTRIBUTES);
        }

        return attributes.toArray();
    }

    /**
     * Generates a set of attributes which will be inserted in the new message class.
     * The number of attributes and its sizes are random but the serialized length is between p_minSize and p_maxSize.
     *
     * @param p_destination
     *         the destination
     * @param p_minSize
     *         the minimum size of the serialized message
     * @param p_maxSize
     *         the maximum size of the serialized message
     * @return an array with initialized attributes
     */
    public static Object[] createWorkload(final short p_destination, final int p_minSize, final int p_maxSize) {
        return createWorkload(p_destination, new Random(SEED).nextInt(p_maxSize - p_minSize + 1) + p_minSize);
    }

    /**
     * Creates the message class for given set of attributes.
     *
     * @param p_parameters
     *         the attributes (parameters for constructor)
     * @return the class
     * @throws ClassCreateException
     *         if the class could not be created
     */
    public static Class createClass(Object... p_parameters) throws ClassCreateException {
        checkNotLoaded();

        create(p_parameters);
        compile();
        return load();
    }

    /**
     * Creates a single attribute and fills it with random data.
     *
     * @param p_list
     *         the list to store the new attribute in
     * @param p_rand
     *         the random number generator
     */
    private static void generateAttribute(final ArrayList<Object> p_list, final Random p_rand) {
        generateAttribute(p_list, p_rand, Integer.MAX_VALUE);
    }

    /**
     * Creates a single attribute and fills it with random data.
     *
     * @param p_list
     *         the list to store the new attribute in
     * @param p_rand
     *         the random number generator
     * @param p_maxSize
     *         the maximum size
     * @return the length of the new attribute
     */
    private static int generateAttribute(final ArrayList<Object> p_list, final Random p_rand, final int p_maxSize) {
        int ret;
        Object attribute = null;
        byte[] array;

        Type[] values = Type.values();
        Type type;

        do {
            ret = 0;
            if (p_maxSize > 1024 * 1024) {
                // Use large types (arrays and Strings) if there is much space left
                type = values[p_rand.nextInt(values.length - 6) + 6];
            } else {
                type = values[p_rand.nextInt(values.length)];
            }
            switch (type) {
                case BYTE:
                    attribute = (byte) p_rand.nextInt();
                    ret = Byte.BYTES;
                    break;
                case SHORT:
                    attribute = (short) p_rand.nextInt();
                    ret = Short.BYTES;
                    break;
                case INT:
                    attribute = p_rand.nextInt();
                    ret = Integer.BYTES;
                    break;
                case LONG:
                    attribute = p_rand.nextLong();
                    ret = Long.BYTES;
                    break;
                case FLOAT:
                    attribute = p_rand.nextFloat();
                    ret = Float.BYTES;
                    break;
                case DOUBLE:
                    attribute = p_rand.nextDouble();
                    ret = Double.BYTES;
                    break;
                case STRING:
                    array = new byte[p_rand.nextInt(Math.min(MAX_STRING_LENGTH, p_maxSize / 2 + 1))];
                    // Fill array with random printable characters from ASCII table (between 33 and 126)
                    for (int i = 0; i < array.length; i++) {
                        array[i] = (byte) (p_rand.nextInt(126 - 33 + 1) + 33);
                    }
                    attribute = new String(array, Charset.forName("US-ASCII"));
                    ret = ObjectSizeUtil.sizeofString((String) attribute);
                    break;
                case BYTE_ARRAY:
                    byte[] byteArray = new byte[p_rand.nextInt(Math.min(MAX_ARRAY_SIZE, p_maxSize / 2 + 1))];
                    for (int i = 0; i < byteArray.length; i++) {
                        byteArray[i] = (byte) p_rand.nextInt();
                    }
                    attribute = byteArray;
                    ret = ObjectSizeUtil.sizeofByteArray((byte[]) attribute);
                    break;
                case SHORT_ARRAY:
                    short[] shortArray = new short[p_rand.nextInt(Math.min(MAX_ARRAY_SIZE, p_maxSize / 2 + 1))];
                    for (int i = 0; i < shortArray.length; i++) {
                        shortArray[i] = (short) p_rand.nextInt();
                    }
                    attribute = shortArray;
                    ret = ObjectSizeUtil.sizeofShortArray((short[]) attribute);
                    break;
                case INT_ARRAY:
                    int[] intArray = new int[p_rand.nextInt(Math.min(MAX_ARRAY_SIZE, p_maxSize / 2 + 1))];
                    for (int i = 0; i < intArray.length; i++) {
                        intArray[i] = p_rand.nextInt();
                    }
                    attribute = intArray;
                    ret = ObjectSizeUtil.sizeofIntArray((int[]) attribute);
                    break;
                case LONG_ARRAY:
                    long[] longArray = new long[p_rand.nextInt(Math.min(MAX_ARRAY_SIZE, p_maxSize / 2 + 1))];
                    for (int i = 0; i < longArray.length; i++) {
                        longArray[i] = p_rand.nextLong();
                    }
                    attribute = longArray;
                    ret = ObjectSizeUtil.sizeofLongArray((long[]) attribute);
                    break;
                default:
            }
        } while (ret > p_maxSize);
        p_list.add(attribute);

        return ret;
    }

    /**
     * Updates the java file (class definition) for given attributes.
     *
     * @param p_parameters
     *         the attributes
     * @throws ClassCreateException
     *         if the class could not be written to file
     */
    private static void create(Object... p_parameters) throws ClassCreateException {
        String classAsString = buildClassString(p_parameters);

        try {
            Files.write(SOURCE_FILE.toPath(), classAsString.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new ClassCreateException("Could not write to java file.");
        }
    }

    /**
     * Builds the class string (to be written to file).
     *
     * @param p_parameters
     *         the attributes
     * @return the class as a string
     */
    private static String buildClassString(Object... p_parameters) {
        StringBuilder globals = new StringBuilder();
        StringBuilder params = new StringBuilder("final short p_destination, final Object[] p_array");
        StringBuilder inits = new StringBuilder();
        StringBuilder writes = new StringBuilder();
        StringBuilder reads = new StringBuilder();
        StringBuilder comparisons = new StringBuilder();

        int counter = 0;
        int len = 0;
        String type = "";
        String writtenType = "";
        String initializer;
        String name = "";
        String access;
        String comparison = "";
        Object obj;
        for (int i = 0; i < p_parameters.length; i++) {
            obj = p_parameters[i];
            access = "p_array[" + i + ']';
            initializer = "";
            if (obj instanceof Byte) {
                type = "Byte";
                writtenType = type;
                initializer = " = new Byte((byte) 0)";
                name = "byte" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Byte.BYTES;
            } else if (obj instanceof Short) {
                type = "Short";
                writtenType = type;
                initializer = " = new Short((short) 0)";
                name = "short" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Short.BYTES;
            } else if (obj instanceof Integer) {
                type = "Integer";
                writtenType = "Int";
                initializer = " = new Integer(0)";
                name = "int" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Integer.BYTES;
            } else if (obj instanceof Long) {
                type = "Long";
                writtenType = type;
                initializer = " = new Long(0)";
                name = "long" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Long.BYTES;
            } else if (obj instanceof Float) {
                type = "Float";
                writtenType = type;
                initializer = " = new Float(0.0F)";
                name = "float" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Float.BYTES;
            } else if (obj instanceof Double) {
                type = "Double";
                writtenType = type;
                initializer = " = new Double(0.0)";
                name = "double" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += Double.BYTES;
            } else if (obj instanceof String) {
                type = "String";
                writtenType = type;
                name = "str" + counter;
                comparison = "this." + name + ".equals((" + type + ") " + access + ')';
                len += ObjectSizeUtil.sizeofString((String) obj);
            } else if (obj instanceof byte[]) {
                type = "byte[]";
                writtenType = "ByteArray";
                name = "byteArray" + counter;
                comparison = "Arrays.equals(this." + name + ", (" + type + ") " + access + ')';
                len += ObjectSizeUtil.sizeofByteArray((byte[]) obj);
            } else if (obj instanceof short[]) {
                type = "short[]";
                writtenType = "ShortArray";
                name = "shortArray" + counter;
                comparison = "Arrays.equals(this." + name + ", (" + type + ") " + access + ')';
                len += ObjectSizeUtil.sizeofShortArray((short[]) obj);
            } else if (obj instanceof int[]) {
                type = "int[]";
                writtenType = "IntArray";
                name = "intArray" + counter;
                comparison = "Arrays.equals(this." + name + ", (" + type + ") " + access + ')';
                len += ObjectSizeUtil.sizeofIntArray((int[]) obj);
            } else if (obj instanceof long[]) {
                type = "long[]";
                writtenType = "LongArray";
                name = "longArray" + counter;
                comparison = "Arrays.equals(this." + name + ", (" + type + ") " + access + ')';
                len += ObjectSizeUtil.sizeofLongArray((long[]) obj);
            }
            globals.append("    private ").append(type).append(' ').append(name).append(initializer).append(";\n");
            inits.append("        this.").append(name).append(" = (").append(type).append(") ").append(access)
                    .append(";\n");
            writes.append("        p_exporter.write").append(writtenType).append('(').append(name).append(");\n");
            reads.append("        ").append(name).append(" = p_importer.read").append(writtenType).append('(')
                    .append(name).append(");\n");
            comparisons.append("        if (!").append(comparison)
                    .append(") { ret = false; System.out.println(\"ERROR (").append(writtenType)
                    .append("s not equal): \" + this.").append(name).append(" + \" != \" + ").append(name)
                    .append("); }").append(";\n");
            counter++;
        }

        String constructors =
                "    public " + CLASS_NAME + "() { super(); }\n\n" + "    public " + CLASS_NAME + '(' + params +
                        ") {\n" + "        super(p_destination, Messages.DXNETMAIN_MESSAGES_TYPE," +
                        " Messages.SUBTYPE_DYNAMIC_MESSAGE);\n" + inits + "    }" + "\n\n";
        String length =
                "    @Override\n    protected final int getPayloadLength() {\n        return " + len + ";\n    }\n\n";
        String write =
                "    @Override\n    protected final void writePayload(final AbstractMessageExporter p_exporter) {\n" +
                        writes + "    }\n\n";
        String read =
                "    @Override\n    protected final void readPayload(final AbstractMessageImporter p_importer) {\n" +
                        reads + "    }\n\n";
        String validate = "    public final boolean validate(" + params.substring(params.indexOf(",") + 2) +
                ") {\n        boolean ret = true;\n" + comparisons + "\n        return ret;\n" + "    }\n\n";

        return HEADER + globals + '\n' + constructors + length + write + read + validate + FOOTER;
    }

    /**
     * Compiles the class and replaces the old class file.
     *
     * @throws ClassCreateException
     *         if the class could not be compiled.
     */
    private static void compile() throws ClassCreateException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        // Add compile destination
        List<String> options = new ArrayList<>();
        //options.add("-d");
        //options.add(
        //        DynamicMessageCreator.class /* we cannot use DynamicMessage.class here as this would load the old class */
        //                .getProtectionDomain().getCodeSource().getLocation().getPath());

        StandardJavaFileManager sfm = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = sfm.getJavaFileObjects(SOURCE_FILE);

        if (!compiler.getTask(null, null, null, options, null, compilationUnits).call()) {
            throw new ClassCreateException("Could not compile class.");
        }
    }

    /**
     * Loads the compiled class.
     *
     * @return the loaded class
     * @throws ClassCreateException
     *         if the new class could not be found or was already loaded
     */
    private static Class load() throws ClassCreateException {
        checkNotLoaded();

        Class ret;
        try {
            ClassLoader classLoader = DynamicMessageCreator.class.getClassLoader();
            ret = classLoader.loadClass(PACKAGE + '.' + CLASS_NAME);
        } catch (ClassNotFoundException e) {
            throw new ClassCreateException("Class could not be loaded." + e);
        }

        return ret;
    }

    /**
     * Checks if the class was not already loaded. We cannot continue if it was (-> inconsistencies).
     *
     * @throws ClassCreateException
     *         if the class could not be checked or was already loaded
     */
    private static void checkNotLoaded() throws ClassCreateException {
        java.lang.reflect.Method method;
        try {
            method = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            method.setAccessible(true);
            ClassLoader classLoader = ClassLoader.getSystemClassLoader();
            if (method.invoke(classLoader, "de.hhu.bsinfo.dxnet.main.messages.DynamicMessage") != null) {
                throw new ClassCreateException(
                        "Class already loaded. Avoid accessing DynamicMessage before compiling and loading.");
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new ClassCreateException("Class could not be checked.");
        }
    }

    /**
     * Exception to throw if the class could not be created.
     *
     * @author Kevin Beineke, kevin.beineke@hhu.de, 06.09.18
     */
    public static final class ClassCreateException extends Exception {

        /**
         * Creates a new instance of ClassCreateException
         *
         * @param p_string
         *         the message
         */
        private ClassCreateException(String p_string) {
            super(p_string);
        }
    }

    /**
     * All supported attribute types.
     *
     * @author Kevin Beineke, kevin.beineke@hhu.de, 06.09.18
     */
    private enum Type {
        BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, BYTE_ARRAY, SHORT_ARRAY, INT_ARRAY, LONG_ARRAY
    }

}