package graphics.scenery.utils

import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.file.*
import java.util.*


class SystemHelpers {
    companion object {
        val logger by LazyLogger()

        /**
         * Sets environment variables during runtime of the process. This code is fractally nasty,
         * but works. Thanks to pushy and mangusbrother at https://stackoverflow.com/a/7201825
         *
         * @param[key] The name of the environment variable
         * @param[value] The value of the environment variable
         */
        fun <K, V> setEnvironmentVariable(key: String, value: String) {
            try {
                /// we obtain the actual environment
                val processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment")
                val theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment")
                val environmentAccessibility = theEnvironmentField.isAccessible
                theEnvironmentField.isAccessible = true

                val env = theEnvironmentField.get(null) as MutableMap<Any, Any>

                if (ExtractsNatives.getPlatform() == ExtractsNatives.Platform.WINDOWS) {
                    // This is all that is needed on windows running java jdk 1.8.0_92
                    if (value == null) {
                        env.remove(key)
                    } else {
                        env.put(key, value)
                    }
                } else {
                    // This is triggered to work on openjdk 1.8.0_91
                    // The ProcessEnvironment$Variable is the key of the map
                    val variableClass = Class.forName("java.lang.ProcessEnvironment\$Variable") as Class<K>
                    val convertToVariable = variableClass.getMethod("valueOf", String::class.java)
                    val conversionVariableAccessibility = convertToVariable.isAccessible
                    convertToVariable.isAccessible = true

                    // The ProcessEnvironment$Value is the value fo the map
                    val valueClass = Class.forName("java.lang.ProcessEnvironment\$Value") as Class<V>
                    val convertToValue = valueClass.getMethod("valueOf", String::class.java)
                    val conversionValueAccessibility = convertToValue.isAccessible
                    convertToValue.isAccessible = true

                    if (value == null) {
                        env.remove(convertToVariable.invoke(null, key))
                    } else {
                        // we place the new value inside the map after conversion so as to
                        // avoid class cast exceptions when rerunning this code
                        env.put(convertToVariable.invoke(null, key), convertToValue.invoke(null, value))

                        // reset accessibility to what they were
                        convertToValue.isAccessible = conversionValueAccessibility
                        convertToVariable.isAccessible = conversionVariableAccessibility
                    }
                }
                // reset environment accessibility
                theEnvironmentField.isAccessible = environmentAccessibility

                // we apply the same to the case insensitive environment
                val theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment")
                val insensitiveAccessibility = theCaseInsensitiveEnvironmentField.isAccessible
                theCaseInsensitiveEnvironmentField.isAccessible = true
                // Not entirely sure if this needs to be casted to ProcessEnvironment$Variable and $Value as well
                val cienv = theCaseInsensitiveEnvironmentField.get(null) as MutableMap<String, String>
                if (value == null) {
                    // remove if null
                    cienv.remove(key)
                } else {
                    cienv.put(key, value)
                }
                theCaseInsensitiveEnvironmentField.isAccessible = insensitiveAccessibility
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("Failed setting environment variable <$key> to <$value>", e)
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException("Failed setting environment variable <$key> to <$value>", e)
            } catch (e: IllegalAccessException) {
                throw IllegalStateException("Failed setting environment variable <$key> to <$value>", e)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("Failed setting environment variable <$key> to <$value>", e)
            } catch (e: NoSuchFieldException) {
                // we could not find theEnvironment
                val env = System.getenv()
                Collections::class.java.declaredClasses
                    // obtain the declared classes of type $UnmodifiableMap
                    .filter { c1 -> "java.util.Collections\$UnmodifiableMap" == c1.name }
                    .map({ c1 ->
                        try {
                            c1.getDeclaredField("m")
                        } catch (e1: NoSuchFieldException) {
                            throw IllegalStateException("Failed setting environment variable <$key> to <$value> when locating in-class memory map of environment", e1)
                        }
                    })
                    .forEach { field ->
                        try {
                            val fieldAccessibility = field.isAccessible()
                            field.setAccessible(true)
                            // we obtain the environment
                            val map = field.get(env) as MutableMap<String, String>
                            if (value == null) {
                                // remove if null
                                map.remove(key)
                            } else {
                                map.put(key, value)
                            }
                            // reset accessibility
                            field.setAccessible(fieldAccessibility)
                        } catch (e1: ConcurrentModificationException) {
                            // This may happen if we keep backups of the environment before calling this method
                            // as the map that we kept as a backup may be picked up inside this block.
                            // So we simply skip this attempt and continue adjusting the other maps
                            // To avoid this one should always keep individual keys/value backups not the entire map
                            logger.debug("Attempted to modify source map: " + field.getDeclaringClass() + "#" + field.getName(), e1)
                        } catch (e1: IllegalAccessException) {
                            throw IllegalStateException("Failed setting environment variable <$key> to <$value>. Unable to access field!", e1)
                        }
                    }
            }

            logger.debug("Set environment variable <" + key + "> to <" + value + ">. Sanity Check: " + System.getenv(key))
        }

        /**
         * Returns a [Path] from a given string, safely checking whether it's on a file or a jar,
         * or whatever filesystem.
         *
         * @param[path] The file to return a [Path] for.
         * @return The [Path] for [path]
         */
        fun getPathFromString(path: String): Path {
            // replace backslash occurences with forward slashes for URI not to stumble
            return getPath(URI.create(path.replace("\\", "/")))
        }

        /**
         * Returns a [Path] from a given [URI], safely checking whether it's on a file or a jar,
         * or whatever filesystem.
         *
         * @param[path] The URI to return a [Path] for.
         * @return The [Path] for [path]
         */
        fun getPath(path: URI): Path {
            return try {
                Paths.get(path)
            } catch (e: FileSystemNotFoundException) {
                val env: Map<String, Any> = emptyMap()
                try {
                    val fs = FileSystems.newFileSystem(path, env)

                    fs.provider().getPath(path)
                } catch(pnfe: ProviderNotFoundException) {
                    FileSystems.getDefault().getPath(path.toString())
                }
            } catch (e : IllegalArgumentException) {
                // handle the case when no scheme is given
                when(ExtractsNatives.getPlatform()) {
                    // on macOS and Linux, we'll just use the default file system and hand over the scheme-less path
                    ExtractsNatives.Platform.MACOS,
                    ExtractsNatives.Platform.LINUX -> FileSystems.getDefault().getPath(path.toString())
                    // on Windows, a leading slash is added, which we remove
                    ExtractsNatives.Platform.WINDOWS -> FileSystems.getDefault().getPath(path.toString().substring(1))
                    else -> {
                        throw IllegalStateException("Don't know how to sanitize path $path on unknown platform.")
                    }
                }
            }
        }
    }
}