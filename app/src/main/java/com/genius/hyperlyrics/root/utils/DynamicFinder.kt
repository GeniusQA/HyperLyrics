package com.genius.hyperlyrics.root.utils

/**
 * 动态发现工具类，用于在混淆后的代码中定位关键 Hook 点。
 */
object DynamicFinder {
    private const val TAG = "DynamicFinder"

    /**
     * 在指定的 ClassLoader 中全量扫描类，寻找包含特定常量字符串（如 TAG）的类。
     */
    fun findClassByConstantString(
        loader: ClassLoader,
        targetPackage: String,
        targetString: String
    ): Class<*>? {
        try {
            // 获取 BaseDexClassLoader 的 pathList。部分框架/匿名 ClassLoader 不是标准
            // BaseDexClassLoader 结构，不能作为错误上报，直接跳过即可。
            val pathListField = findFieldInHierarchy(loader.javaClass, "pathList") ?: return null
            pathListField.isAccessible = true
            val pathList = pathListField.get(loader)

            // 获取 DexPathList 的 dexElements
            val dexElementsField = findFieldInHierarchy(pathList.javaClass, "dexElements") ?: return null
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as Array<*>

            for (element in dexElements) {
                if (element == null) continue
                val dexFileField = findFieldInHierarchy(element.javaClass, "dexFile") ?: continue
                dexFileField.isAccessible = true
                val dexFile = dexFileField.get(element) as? dalvik.system.DexFile ?: continue

                @Suppress("DEPRECATION")
                val entries = dexFile.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (className.startsWith(targetPackage)) {
                        try {
                            val clazz = loader.loadClass(className)
                            // 检查类中的静态常量字段（通常 TAG 是 static final String）
                            for (field in clazz.declaredFields) {
                                if (field.type == String::class.java) {
                                    field.isAccessible = true
                                    val value = field.get(null) as? String
                                    if (value == targetString) {
                                        HookLogger.d(
                                            TAG,
                                            "按特征字符串找到类: target=$targetString, class=$className"
                                        )
                                        return clazz
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            // 忽略加载失败的类
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.w("DynamicFinder", "扫描 Dex 寻找特征字符串时跳过异常 ClassLoader: $targetString", e)
        }
        return null
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

}
