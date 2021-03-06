/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.d.config;

import static com.google.common.base.Preconditions.checkArgument;
import java.util.Optional;

import org.onosproject.net.DeviceId;
import org.onosproject.yang.model.KeyLeaf;
import org.onosproject.yang.model.ListKey;
import org.onosproject.yang.model.NodeKey;
import org.onosproject.yang.model.ResourceId;

import com.google.common.annotations.Beta;

/**
 * Utility related to device ResourceId.
 */
@Beta
public abstract class DeviceResourceIds {

    // assuming following device tree structure
    // - "/"
    //    +- devices
    //         +- device (=device root node:ListKey)


    // FIXME randomly defined namespace, replace with something appropriate
    /**
     * SchemaId namespace for DCS defined nodes.
     */
    public static final String DCS_NAMESPACE = "org.onosproject.dcs";

    /**
     * SchemaId name for root node.
     */
    public static final String ROOT_NAME = "/";
    /**
     * SchemaId name for devices node.
     */
    public static final String DEVICES_NAME = "devices";
    /**
     * SchemaId name for device node.
     */
    public static final String DEVICE_NAME = "device";
    /**
     * KeyLeaf {@code name}, which holds DeviceId information on device node.
     */
    public static final String DEVICE_ID_KL_NAME = "device-id";

    /**
     * ResourceId pointing at root node.
     */
    public static final ResourceId ROOT_ID = ResourceId.builder()
            .addBranchPointSchema(ROOT_NAME, DCS_NAMESPACE)
            .build();

    static final NodeKey<?> ROOT_NODE =
            NodeKey.builder().schemaId(ROOT_NAME, DCS_NAMESPACE).build();

    /**
     * nodeKeys index for root node.
     */
    static final int ROOT_INDEX = 0;
    /**
     * nodeKeys index for devices node.
     */
    static final int DEVICES_INDEX = 1;
    /**
     * nodeKeys index for device node.
     */
    static final int DEVICE_INDEX = 2;

    /**
     * Tests if specified path points to root node of a Device.
     *
     * @param path to test.
     * @return true if path points to root node of a Device.
     */
    public static boolean isDeviceRootNode(ResourceId path) {
        return path.nodeKeys().size() == 3 &&
               isUnderDeviceRootNode(path);
    }

    /**
     * Tests if specified path points to root node of a Device.
     *
     * @param path to test.
     * @return true if path points to root node of a Device.
     */
    public static boolean isUnderDeviceRootNode(ResourceId path) {
        return path.nodeKeys().size() >= 3 &&
                // TODO Would be better to test whole schemeId
                DEVICE_NAME.equals(path.nodeKeys().get(DEVICE_INDEX).schemaId().name()) &&
                (path.nodeKeys().get(DEVICE_INDEX) instanceof ListKey) &&
                // TODO Would be better to test whole schemeId
                DEVICES_NAME.equals(path.nodeKeys().get(DEVICES_INDEX).schemaId().name()) &&
                ROOT_NODE.equals(path.nodeKeys().get(ROOT_INDEX));
    }

    /**
     * Tests if specified path points to root or devices node.
     *
     * @param path to test.
     * @return true if path points to root node of a Device.
     */
    public static boolean isRootOrDevicesNode(ResourceId path) {
        return isDevicesNode(path) ||
               isRootNode(path);
    }

    public static boolean isDevicesNode(ResourceId path) {
        return path.nodeKeys().size() == 2 &&
                // TODO Would be better to test whole schemeId
                DEVICES_NAME.equals(path.nodeKeys().get(DEVICES_INDEX).schemaId().name()) &&
                ROOT_NODE.equals(path.nodeKeys().get(ROOT_INDEX));
    }

    public static boolean isRootNode(ResourceId path) {
        return path.nodeKeys().size() == 1 &&
                ROOT_NODE.equals(path.nodeKeys().get(ROOT_INDEX));
    }

    /**
     * Transforms device resource path to DeviceId.
     *
     * @param path pointing to somewhere in the subtree of a device
     * @return DeviceId
     * @throws IllegalArgumentException if the path was not part of devices tree
     */
    public static DeviceId toDeviceId(ResourceId path) {
        checkArgument(isUnderDeviceRootNode(path), path);
        // FIXME if we decide to drop any of intermediate nodes
        //        "/" - "devices" - "device"
        return toDeviceId(path.nodeKeys().get(DEVICE_INDEX));
    }

    /**
     * Transforms root node of a device to corresponding DeviceId.
     *
     * @param deviceRoot NodeKey of a device root node
     * @return DeviceId
     * @throws IllegalArgumentException if not a device node
     */
    public static DeviceId toDeviceId(NodeKey<?> deviceRoot) {
        // TODO Would be better to test whole schemeId
        if (!DEVICE_NAME.equals(deviceRoot.schemaId().name())) {
            throw new IllegalArgumentException(deviceRoot + " is not a device node");
        }

        if (deviceRoot instanceof ListKey) {
            ListKey device = (ListKey) deviceRoot;
            Optional<DeviceId> did = device.keyLeafs().stream()
                // TODO If we decide to define ONOS schema for device ID,
                // use whole schemaId to filter, not only by name
                .filter(kl -> DEVICE_ID_KL_NAME.equals(kl.leafSchema().name()))
                .map(KeyLeaf::leafValAsString)
                .map(DeviceId::deviceId)
                .findFirst();

            if (did.isPresent()) {
                return did.get();
            }
            throw new IllegalArgumentException("device-id not found in " + deviceRoot);
        }
        throw new IllegalArgumentException("Unexpected type " + deviceRoot.getClass());
    }

    /**
     * Transforms DeviceId into a ResourceId pointing to device root node.
     *
     * @param deviceId to transform
     * @return ResourceId
     */
    public static ResourceId toResourceId(DeviceId deviceId) {
        return ResourceId.builder()
                .addBranchPointSchema(ROOT_NAME, DCS_NAMESPACE)
                .addBranchPointSchema(DEVICES_NAME, DCS_NAMESPACE)
                .addBranchPointSchema(DEVICE_NAME, DCS_NAMESPACE)
                .addKeyLeaf(DEVICE_ID_KL_NAME, DCS_NAMESPACE, deviceId.toString())
                .build();
    }

}
