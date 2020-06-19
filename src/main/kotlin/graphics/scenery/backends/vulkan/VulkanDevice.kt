package graphics.scenery.backends.vulkan

import graphics.scenery.backends.vulkan.VulkanDevice.DescriptorPool.Companion.maxSets
import graphics.scenery.backends.vulkan.VulkanDevice.DescriptorPool.Companion.maxTextures
import graphics.scenery.utils.LazyLogger
import org.lwjgl.system.MemoryStack.create
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memUTF8
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM
import org.lwjgl.vulkan.VK11.VK_FORMAT_G8B8G8R8_422_UNORM
import java.util.*

typealias QueueIndexWithProperties = Pair<Int, VkQueueFamilyProperties>

/**
 * Describes a Vulkan device attached to an [instance] and a [physicalDevice].
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
open class VulkanDevice(val instance: VkInstance, val physicalDevice: VkPhysicalDevice, val deviceData: DeviceData, extensionsQuery: (VkPhysicalDevice) -> Array<String> = { arrayOf() }, validationLayers: Array<String> = arrayOf(), headless: Boolean = false) {

    protected val logger by LazyLogger()
    /** Stores available memory types on the device. */
    val memoryProperties: VkPhysicalDeviceMemoryProperties
    /** Stores the Vulkan-internal device. */
    val vulkanDevice: VkDevice
    /** Stores available queue indices. */
    val queues: Queues
    /** Stores available extensions */
    val extensions = ArrayList<String>()

    private val descriptorPools = ArrayList<DescriptorPool>(5)

    /**
     * Enum class for GPU device types. Can be unknown, other, integrated, discrete, virtual or CPU.
     */
    enum class DeviceType { Unknown, Other, IntegratedGPU, DiscreteGPU, VirtualGPU, CPU }

    /**
     * Class to store device-specific metadata.
     *
     * @property[vendor] The vendor name of the device.
     * @property[name] The name of the device.
     * @property[driverVersion] The driver version as represented as string.
     * @property[apiVersion] The Vulkan API version supported by the device, represented as string.
     * @property[type] The [DeviceType] of the GPU.
     */
    data class DeviceData(val vendor: String, val name: String, val driverVersion: String, val apiVersion: String, val type: DeviceType, val properties: VkPhysicalDeviceProperties, val formats: Map<Int, VkFormatProperties>) {
        fun toFullString() = "$vendor $name ($type, driver version $driverVersion, Vulkan API $apiVersion)"
    }

    /**
     * Data class to store device-specific queue indices.
     *
     * @property[presentQueue] The index of the present queue
     * @property[graphicsQueue] The index of the graphics queue
     * @property[computeQueue] The index of the compute queue
     */
    data class Queues(val presentQueue: QueueIndexWithProperties, val transferQueue: QueueIndexWithProperties, val graphicsQueue: QueueIndexWithProperties, val computeQueue: QueueIndexWithProperties)

    init {
        val result = stackPush().use { stack ->
            val pQueueFamilyPropertyCount = stack.callocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null)
            val queueCount = pQueueFamilyPropertyCount.get(0)
            val queueProps = VkQueueFamilyProperties.calloc(queueCount)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps)

            var graphicsQueueFamilyIndex = 0
            var transferQueueFamilyIndex = 0
            var computeQueueFamilyIndex = 0
            val presentQueueFamilyIndex = 0
            var index = 0

            while (index < queueCount) {
                if (queueProps.get(index).queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
                    graphicsQueueFamilyIndex = index
                }

                if (queueProps.get(index).queueFlags() and VK_QUEUE_TRANSFER_BIT != 0) {
                    transferQueueFamilyIndex = index
                }

                if (queueProps.get(index).queueFlags() and VK_QUEUE_COMPUTE_BIT != 0) {
                    computeQueueFamilyIndex = index
                }

                index++
            }

            val requiredFamilies = listOf(
                graphicsQueueFamilyIndex,
                transferQueueFamilyIndex,
                computeQueueFamilyIndex)
                .groupBy { it }

            logger.info("Creating ${requiredFamilies.size} distinct queue groups")

            /**
             * Adjusts the queue count of a [VkDeviceQueueCreateInfo] struct to [num].
             */
            fun VkDeviceQueueCreateInfo.queueCount(num: Int): VkDeviceQueueCreateInfo {
                VkDeviceQueueCreateInfo.nqueueCount(this.address(), num)
                return this
            }

            val queueCreateInfo = VkDeviceQueueCreateInfo.callocStack(requiredFamilies.size, stack)

            requiredFamilies.entries.forEachIndexed { i, (familyIndex, group) ->
                val size = minOf(queueProps.get(familyIndex).queueCount(), group.size)
                logger.debug("Adding queue with familyIndex=$familyIndex, size=$size")

                val pQueuePriorities = stack.callocFloat(group.size)
                for(pr in 0 until group.size) { pQueuePriorities.put(pr, 1.0f) }

                queueCreateInfo[i]
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(familyIndex)
                    .pQueuePriorities(pQueuePriorities)
                    .queueCount(size)
            }

            val extensionsRequested = extensionsQuery.invoke(physicalDevice)
            logger.debug("Requested extensions: ${extensionsRequested.joinToString(", ")} ${extensionsRequested.size}")
            val utf8Exts = extensionsRequested.map { stack.UTF8(it) }

            // allocate enough pointers for required extensions, plus the swapchain extension
            // if we are not running in headless mode
            val extensions = if(!headless) {
                val e = stack.callocPointer(1 + extensionsRequested.size)
                e.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                e
            } else {
                stack.callocPointer(extensionsRequested.size)
            }

            utf8Exts.forEach { extensions.put(it) }
            extensions.flip()

            if(validationLayers.isNotEmpty()) {
                logger.warn("Enabled Vulkan API validations. Expect degraded performance.")
            }

            val ppEnabledLayerNames = stack.callocPointer(validationLayers.size)
            var i = 0
            while (i < validationLayers.size) {
                ppEnabledLayerNames.put(memUTF8(validationLayers[i]))
                i++
            }
            ppEnabledLayerNames.flip()

            val enabledFeatures = VkPhysicalDeviceFeatures.callocStack(stack)
                .samplerAnisotropy(true)
                .largePoints(true)
                .geometryShader(true)

            val deviceCreateInfo = VkDeviceCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(MemoryUtil.NULL)
                .pQueueCreateInfos(queueCreateInfo)
                .ppEnabledExtensionNames(extensions)
                .ppEnabledLayerNames(ppEnabledLayerNames)
                .pEnabledFeatures(enabledFeatures)

            logger.debug("Creating device...")
            val pDevice = stack.callocPointer(1)
            val err = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice)
            val device = pDevice.get(0)

            if (err != VK_SUCCESS) {
                throw IllegalStateException("Failed to create device: " + VU.translate(err))
            }
            logger.debug("Device successfully created.")

            val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc()
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties)

            VulkanRenderer.DeviceAndGraphicsQueueFamily(VkDevice(device, physicalDevice, deviceCreateInfo),
                graphicsQueueFamilyIndex, computeQueueFamilyIndex, presentQueueFamilyIndex, transferQueueFamilyIndex, memoryProperties)

            Triple(VkDevice(device, physicalDevice, deviceCreateInfo),
                Queues(
                    presentQueue = presentQueueFamilyIndex to queueProps[presentQueueFamilyIndex],
                    transferQueue = transferQueueFamilyIndex to queueProps[transferQueueFamilyIndex],
                    computeQueue = computeQueueFamilyIndex to queueProps[computeQueueFamilyIndex],
                    graphicsQueue = graphicsQueueFamilyIndex to queueProps[graphicsQueueFamilyIndex]),
                memoryProperties
            )
        }

        vulkanDevice = result.first
        queues = result.second
        memoryProperties = result.third

        extensions.addAll(extensionsQuery.invoke(physicalDevice))
        extensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)

        descriptorPools.add(createDescriptorPool())

        logger.debug("Created logical Vulkan device on ${deviceData.vendor} ${deviceData.name}")
    }

    /**
     * Returns if a given format [feature] is supported for a given [format].
     * Assume [optimalTiling], otherwise optimal tiling.
     */
    fun formatFeatureSupported(format: Int, feature: Int, optimalTiling: Boolean): Boolean {
        val properties = deviceData.formats[format] ?: return false

        return if(optimalTiling) {
            properties.linearTilingFeatures() and feature != 0
        } else {
            properties.optimalTilingFeatures() and feature != 0
        }
    }

    /**
     * Returns the available memory types on this devices that
     * bear [typeBits] and [flags]. May return an empty list in case
     * the device does not support the given types and flags.
     */
    fun getMemoryType(typeBits: Int, flags: Int): List<Int> {
        var bits = typeBits
        val types = ArrayList<Int>(5)

        for (i in 0 until memoryProperties.memoryTypeCount()) {
            if (bits and 1 == 1) {
                if ((memoryProperties.memoryTypes(i).propertyFlags() and flags) == flags) {
                    types.add(i)
                }
            }

            bits = bits shr 1
        }

        if(types.isEmpty()) {
            logger.warn("Memory type $flags not found for device $this (${vulkanDevice.address().toHexString()}")
        }

        return types
    }

    /**
     * Creates a command pool with a given [queueNodeIndex] for this device.
     */
    fun createCommandPool(queueNodeIndex: Int): Long {
        return stackPush().use { stack ->
            val cmdPoolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queueNodeIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)

            val pCmdPool = stack.callocLong(1)
            val err = vkCreateCommandPool(vulkanDevice, cmdPoolInfo, null, pCmdPool)
            val commandPool = pCmdPool.get(0)

            if (err != VK_SUCCESS) {
                throw IllegalStateException("Failed to create command pool: " + VU.translate(err))
            }

            logger.debug("Created command pool ${commandPool.toHexString()}")
            commandPool
        }
    }

    /**
     * Destroys the command pool given by [commandPool].
     */
    fun destroyCommandPool(commandPool: Long) {
        vkDestroyCommandPool(vulkanDevice, commandPool, null)
    }

    /**
     * Returns a string representation of this device.
     */
    override fun toString(): String {
        return "${deviceData.vendor} ${deviceData.name}"
    }

    /**
     * Destroys this device, waiting for all operations to finish before.
     */
    fun close() {
        logger.debug("Closing device ${deviceData.vendor} ${deviceData.name}...")
        deviceData.formats.forEach { (_, props) ->
            props.free()
        }

        vkDeviceWaitIdle(vulkanDevice)

        descriptorPools.forEach {
            vkDestroyDescriptorPool(vulkanDevice, it.handle, null)
        }
        descriptorPools.clear()

        vkDestroyDevice(vulkanDevice, null)
        logger.debug("Device closed.")

        memoryProperties.free()
    }


    data class DescriptorPool(val handle: Long, var free: Int = maxTextures + maxUBOs + maxInputAttachments + maxUBOs) {

        companion object {
            val maxTextures = 2048 * 16
            val maxUBOs = 2048
            val maxInputAttachments = 32
            val maxSets = maxUBOs * 2 + maxInputAttachments + maxTextures
        }
    }

    private fun createDescriptorPool(): DescriptorPool {

        return stackPush().use { stack ->
            // We need to tell the API the number of max. requested descriptors per type
            val typeCounts = VkDescriptorPoolSize.callocStack(4, stack)
            typeCounts[0]
                .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(DescriptorPool.maxTextures)

            typeCounts[1]
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                .descriptorCount(DescriptorPool.maxUBOs)

            typeCounts[2]
                .type(VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT)
                .descriptorCount(DescriptorPool.maxInputAttachments)

            typeCounts[3]
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(DescriptorPool.maxUBOs)

            // Create the global descriptor pool
            // All descriptors used in this example are allocated from this pool
            val descriptorPoolInfo = VkDescriptorPoolCreateInfo.callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pNext(MemoryUtil.NULL)
                .pPoolSizes(typeCounts)
                .maxSets(DescriptorPool.maxSets)// Set the max. number of sets that can be requested
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)

            val handle = VU.getLong("vkCreateDescriptorPool",
                { vkCreateDescriptorPool(vulkanDevice, descriptorPoolInfo, null, this) }, {})

            DescriptorPool(handle, maxSets)
        }
    }

    /**
     * Creates a new descriptor set with default type uniform buffer.
     */
    fun createDescriptorSet(
        descriptorSetLayout: Long,
        bindingCount: Int,
        ubo: VulkanUBO.UBODescriptor,
        type: Int = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
    ): Long {
        val pool = findAvailableDescriptorPool()
        pool.free -= 1

        return VU.createDescriptorSet(this, pool.handle, descriptorSetLayout, bindingCount, ubo, type)
    }

    /**
     * Creates a new dynamic descriptor set.
     */
    fun createDescriptorSetDynamic(
        descriptorSetLayout: Long,
        bindingCount: Int,
        buffer: VulkanBuffer
    ): Long {
        val pool = findAvailableDescriptorPool()
        pool.free -= 1

        return VU.createDescriptorSetDynamic(this, pool.handle, descriptorSetLayout, bindingCount, buffer)
    }

    /**
     * Creates and returns a new descriptor set layout on this device with the members declared in [types], which is
     * a [List] of a Pair of a type, associated with a count (e.g. Dynamic UBO to 1). The base binding can be set with [binding].
     * The shader stages to which the DSL should be visible can be set via [shaderStages].
     */
    fun createDescriptorSetLayout(types: List<Pair<Int, Int>>, binding: Int = 0, shaderStages: Int): Long {
        return VU.createDescriptorSetLayout(this, types, binding, shaderStages)
    }

    /**
     * Creates and returns a new descriptor set layout on this device with one member of [type], which is by default a
     * dynamic uniform buffer. The [binding] and number of descriptors ([descriptorNum], [descriptorCount]) can be
     * customized,  as well as the shader stages to which the DSL should be visible ([shaderStages]).
     */
    fun createDescriptorSetLayout(type: Int = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, binding: Int = 0, descriptorNum: Int = 1, descriptorCount: Int = 1, shaderStages: Int = VK_SHADER_STAGE_ALL): Long {
        return VU.createDescriptorSetLayout(this, type, binding, descriptorNum, descriptorCount, shaderStages)
    }

    /**
     * Creates a new descriptor set for a render [target] given.
     */
    fun createRenderTargetDescriptorSet(
        descriptorSetLayout: Long,
        target: VulkanFramebuffer,
        imageLoadStore: Boolean = false,
        onlyFor: List<VulkanFramebuffer.VulkanFramebufferAttachment>? = null
    ): Long {
        val pool = findAvailableDescriptorPool()
        pool.free -= 1

        return VU.createRenderTargetDescriptorSet(this, pool.handle, descriptorSetLayout, target, imageLoadStore, onlyFor)
    }

    /**
     * Finds and returns the first available descriptor pool that can provide at least
     * [requiredSets] descriptor sets.
     *
     * Creates a new pool if necessary.
     */
    fun findAvailableDescriptorPool(requiredSets: Int = 1): DescriptorPool {
        val available = descriptorPools.firstOrNull { it.free >= requiredSets }

        return if(available == null) {
            descriptorPools.add(createDescriptorPool())
            descriptorPools.first { it.free >= requiredSets }
        } else {
            available
        }
    }

    /**
     * Utility functions for [VulkanDevice].
     */
    companion object {
        val logger by LazyLogger()

        /**
         * Data class for defining device/driver-specific workarounds.
         *
         * @property[filter] A lambda to define the condition to trigger this workaround, must return a boolean.
         * @property[description] A string description of the cause and effects of the workaround
         * @property[workaround] A lambda that will be executed if this [DeviceWorkaround] is triggered.
         */
        data class DeviceWorkaround(val filter: (DeviceData) -> Boolean, val description: String, val workaround: (DeviceData) -> Any)


        val deviceWorkarounds: List<DeviceWorkaround> = listOf(
//            DeviceWorkaround(
//                { it.vendor == "Nvidia" && it.driverVersion.substringBefore(".").toInt() >= 396 },
//                "Nvidia 396.xx series drivers are unsupported due to crashing bugs in the driver") {
//                if(System.getenv("__GL_NextGenCompiler") == null) {
//                    logger.warn("The graphics driver version you are using (${it.driverVersion}) contains a bug that prevents scenery's Vulkan renderer from functioning correctly.")
//                    logger.warn("Please set the environment variable __GL_NextGenCompiler to 0 and restart the application to work around this issue.")
//                    logger.warn("For this session, scenery will fall back to the OpenGL renderer in 20 seconds.")
//                    Thread.sleep(20000)
//
//                    throw RuntimeException("Bug in graphics driver, falling back to OpenGL")
//                }
//            }
        )

        private fun toDeviceType(vkDeviceType: Int): DeviceType {
            return when(vkDeviceType) {
                0 -> DeviceType.Other
                1 -> DeviceType.IntegratedGPU
                2 -> DeviceType.DiscreteGPU
                3 -> DeviceType.VirtualGPU
                4 -> DeviceType.CPU
                else -> DeviceType.Unknown
            }
        }

        private fun vendorToString(vendor: Int): String =
            when(vendor) {
                0x1002 -> "AMD"
                0x10DE -> "Nvidia"
                0x8086 -> "Intel"
                else -> "(Unknown vendor)"
            }

        private fun decodeDriverVersion(version: Int) =
            Triple(
                version and 0xFFC00000.toInt() shr 22,
                version and 0x003FF000 shr 12,
                version and 0x00000FFF
            )

        /**
         * Gets the supported format ranges for image formats,
         * adapted from https://github.com/KhronosGroup/Vulkan-Tools/blob/master/vulkaninfo/vulkaninfo.h
         *
         * Ranges are additive!
         */
        private val supportedFormatRanges = hashMapOf(
            (1 to 0) to (VK_FORMAT_UNDEFINED .. VK_FORMAT_ASTC_12x12_SRGB_BLOCK),
            (1 to 1) to (VK_FORMAT_G8B8G8R8_422_UNORM .. VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM)
        )

        private fun driverVersionToString(version: Int) =
            decodeDriverVersion(version).toList().joinToString(".")

        /**
         * Creates a [VulkanDevice] in a given [instance] from a physical device, requesting extensions
         * given by [additionalExtensions]. The device selection is done in a fuzzy way by [physicalDeviceFilter],
         * such that one can filter for certain vendors, e.g.
         */
        @JvmStatic fun fromPhysicalDevice(instance: VkInstance, physicalDeviceFilter: (Int, DeviceData) -> Boolean,
                                          additionalExtensions: (VkPhysicalDevice) -> Array<String> = { arrayOf() },
                                          validationLayers: Array<String> = arrayOf(),
                                          headless: Boolean = false): VulkanDevice {
            return stackPush().use { stack ->

                val physicalDeviceCount = VU.getInt("Enumerate physical devices") {
                    vkEnumeratePhysicalDevices(instance, this, null)
                }

                if (physicalDeviceCount < 1) {
                    throw IllegalStateException("No Vulkan-compatible devices found!")
                }

                val physicalDevices = VU.getPointers("Getting Vulkan physical devices", physicalDeviceCount) {
                    vkEnumeratePhysicalDevices(instance, intArrayOf(physicalDeviceCount), this)
                }

                var devicePreference = 0

                logger.info("Physical devices: ")
                val deviceList = ArrayList<DeviceData>(10)

                for (i in 0 until physicalDeviceCount) {
                    val device = VkPhysicalDevice(physicalDevices.get(i), instance)
                    val properties: VkPhysicalDeviceProperties = VkPhysicalDeviceProperties.calloc()
                    val apiVersion = with(decodeDriverVersion(properties.apiVersion())) { this.first to this.second }

                    val formatRanges = (0 .. apiVersion.second).mapNotNull { minor -> supportedFormatRanges[1 to minor] }

                    val formats = formatRanges.flatMap { range ->
                        range.map { format ->
                            val formatProperties = VkFormatProperties.calloc()

                            vkGetPhysicalDeviceFormatProperties(device, format, formatProperties)

                            format to formatProperties
                        }
                    }.toMap()

                    val deviceData = DeviceData(
                        vendor = vendorToString(properties.vendorID()),
                        name = properties.deviceNameString(),
                        driverVersion = driverVersionToString(properties.driverVersion()),
                        apiVersion = driverVersionToString(properties.apiVersion()),
                        type = toDeviceType(properties.deviceType()),
                        properties = properties,
                        formats = formats)

                    if(physicalDeviceFilter.invoke(i, deviceData)) {
                        logger.debug("Device filter matches device $i, $deviceData")
                        devicePreference = i
                    }

                    deviceList.add(deviceData)
                }

                deviceList.forEachIndexed { i, device ->
                    val selected = if (devicePreference == i) {
                        "(selected)"
                    } else {
                        device.properties.free()
                        ""
                    }

                    logger.info("  $i: ${device.toFullString()} $selected")
                }

                val selectedDevice = physicalDevices.get(devicePreference)
                val selectedDeviceData = deviceList[devicePreference]

                if(System.getProperty("scenery.DisableDeviceWorkarounds", "false")?.toBoolean() != true) {
                    deviceWorkarounds.forEach {
                        if (it.filter.invoke(selectedDeviceData)) {
                            logger.warn("Workaround activated: ${it.description}")
                            it.workaround.invoke(selectedDeviceData)
                        }
                    }
                } else {
                    logger.warn("Device-specific workarounds disabled upon request, expect weird things to happen.")
                }

                val physicalDevice = VkPhysicalDevice(selectedDevice, instance)

                physicalDevices.free()

                VulkanDevice(instance, physicalDevice, selectedDeviceData, additionalExtensions, validationLayers, headless)
            }
        }
    }
}
