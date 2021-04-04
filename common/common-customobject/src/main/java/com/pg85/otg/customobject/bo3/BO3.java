package com.pg85.otg.customobject.bo3;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import com.pg85.otg.constants.Constants;
import com.pg85.otg.constants.SettingsEnums.ConfigMode;
import com.pg85.otg.customobject.CustomObjectManager;
import com.pg85.otg.customobject.bo3.bo3function.BO3BlockFunction;
import com.pg85.otg.customobject.bo3.bo3function.BO3EntityFunction;
import com.pg85.otg.customobject.bo3.bo3function.BO3ModDataFunction;
import com.pg85.otg.customobject.bo3.bo3function.BO3ParticleFunction;
import com.pg85.otg.customobject.bo3.bo3function.BO3SpawnerFunction;
import com.pg85.otg.customobject.bo3.checks.BO3Check;
import com.pg85.otg.customobject.config.CustomObjectResourcesManager;
import com.pg85.otg.customobject.config.io.FileSettingsReaderBO4;
import com.pg85.otg.customobject.config.io.FileSettingsWriterBO4;
import com.pg85.otg.customobject.structures.Branch;
import com.pg85.otg.customobject.structures.CustomStructure;
import com.pg85.otg.customobject.structures.CustomStructureCache;
import com.pg85.otg.customobject.structures.CustomStructureCoordinate;
import com.pg85.otg.customobject.structures.StructuredCustomObject;
import com.pg85.otg.customobject.structures.bo3.BO3CustomStructure;
import com.pg85.otg.customobject.structures.bo3.BO3CustomStructureCoordinate;
import com.pg85.otg.customobject.util.BoundingBox;
import com.pg85.otg.customobject.util.StructurePartSpawnHeight;
import com.pg85.otg.customobject.util.BO3Enums.OutsideSourceBlock;
import com.pg85.otg.customobject.util.BO3Enums.SpawnHeightEnum;
import com.pg85.otg.exception.InvalidConfigException;
import com.pg85.otg.logging.ILogger;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.bo3.Rotation;
import com.pg85.otg.util.helpers.MathHelper;
import com.pg85.otg.util.helpers.RandomHelper;
import com.pg85.otg.util.interfaces.IMaterialReader;
import com.pg85.otg.util.interfaces.IModLoadedChecker;
import com.pg85.otg.util.interfaces.IWorldGenRegion;
import com.pg85.otg.util.materials.LocalMaterialData;
import com.pg85.otg.util.materials.LocalMaterials;

public class BO3 implements StructuredCustomObject
{
    private BO3Config settings;
    private final String name;
    private final File file;
    private boolean isInvalidConfig;

    /**
     * Creates a BO3 from a file.
     *
     * @param name Name of the BO3.
     * @param file File of the BO3. If the file does not exist, a BO3 with the default settings is created.
     */
    BO3(String name, File file)
    {
        this.name = name;
        this.file = file;
    }

    @Override
    public String getName()
    {
        return this.name;
    }

    public BO3Config getSettings()
    {
        return this.settings;
    }

    @Override
    public boolean onEnable(String presetName, Path otgRootFolder, boolean spawnLog, ILogger logger, CustomObjectManager customObjectManager, IMaterialReader materialReader, CustomObjectResourcesManager manager, IModLoadedChecker modLoadedChecker)
    {
    	if(this.isInvalidConfig)
    	{
    		return false;
    	}
    	if(this.settings != null)
    	{
    		return true;
    	}
        try
        {
            this.settings = new BO3Config(new FileSettingsReaderBO4(this.name, this.file, logger), presetName, otgRootFolder, spawnLog, logger, customObjectManager, materialReader, manager, modLoadedChecker);
            if (this.settings.settingsMode != ConfigMode.WriteDisable)
            {
                FileSettingsWriterBO4.writeToFile(this.settings, this.settings.settingsMode, spawnLog, logger, materialReader, manager);
            }
        }
        catch (InvalidConfigException ex)
        {
        	this.isInvalidConfig = true;
            return false;
        }
        return true;
    }

    @Override
    public boolean canSpawnAsTree()
    {
        return this.settings.tree;
    }

    // Used for saplings
    @Override
    public boolean canRotateRandomly()
    {
        return this.settings.rotateRandomly;
    }
    
	@Override
	public boolean loadChecks(IModLoadedChecker modLoadedChecker)
	{
		return this.settings == null ? false : this.settings.parseModChecks(modLoadedChecker);
	}

    // Used to safely spawn this object from a grown sapling
    @Override
    public boolean spawnFromSapling(CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, Rotation rotation, int x, int y, int z)
    {
        BO3BlockFunction[] blocks = this.settings.getBlocks(rotation.getRotationId());

        ArrayList<BO3BlockFunction> blocksToSpawn = new ArrayList<BO3BlockFunction>();

        ObjectExtrusionHelper oeh = new ObjectExtrusionHelper(this.settings.extrudeMode, this.settings.extrudeThroughBlocks);
        HashSet<ChunkCoordinate> chunks = new HashSet<ChunkCoordinate>();

        LocalMaterialData localMaterial;
        for (BO3BlockFunction block : blocks)
        {
            localMaterial = worldGenRegion.getMaterial(x + block.x, y + block.y, z + block.z, null);

            // Ignore blocks in the ground when checking spawn conditions
            if (block.y >= 0)
            {
                // Do not spawn if non-tree blocks are in the way
                if (
            		!localMaterial.isAir() && 
            		!localMaterial.isLogOrLeaves() && 
            		!localMaterial.isMaterial(LocalMaterials.SAPLING)
        		)
                {
                    return false;
                }
            }

            // Only overwrite air
            if (localMaterial.isAir())
            {
                chunks.add(ChunkCoordinate.fromBlockCoords(x + block.x, z + block.z));
                blocksToSpawn.add(block);
            }

            oeh.addBlock((BO3BlockFunction) block);
        }
        for (BO3BlockFunction block : blocksToSpawn)
        {
            block.spawn(worldGenRegion, random, x + block.x, y + block.y, z + block.z, null, false);
        }
        oeh.extrude(worldGenRegion, random, x, y, z, null, false);
        handleBO3Functions(null, structureCache, worldGenRegion, random, rotation, x, y, z, chunks, null);

        return true;
    }

    // Force spawns a BO3 object. Used by /otg spawn and bo3AtSpawn.
    // This method ignores the maxPercentageOutsideBlock setting
    @Override
    public boolean spawnForced(CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, Rotation rotation, int x, int y, int z)
    {
        BO3BlockFunction[] blocks = this.settings.getBlocks(rotation.getRotationId());
        ObjectExtrusionHelper oeh = new ObjectExtrusionHelper(this.settings.extrudeMode, this.settings.extrudeThroughBlocks);
        HashSet<ChunkCoordinate> chunks = new HashSet<ChunkCoordinate>();

        for (BO3BlockFunction block : blocks)
        {
            block.spawn(worldGenRegion, random, x + block.x, y + block.y, z + block.z, null, this.doReplaceBlocks());
            oeh.addBlock(block);
            chunks.add(ChunkCoordinate.fromBlockCoords(x + block.x, z + block.z));
        }

        oeh.extrude(worldGenRegion, random, x, y, z, null, this.doReplaceBlocks());
        handleBO3Functions(null, structureCache, worldGenRegion, random, rotation, x, y, z, chunks, null);

        return true;
    }

    // This method is only used to spawn CustomObject.
    // Called during population.
    @Override
    public boolean process(CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, ChunkCoordinate chunkCoord)
    {
        boolean atLeastOneObjectHasSpawned = false;

        int chunkMiddleX = chunkCoord.getBlockXCenter();
        int chunkMiddleZ = chunkCoord.getBlockZCenter();
        for (int i = 0; i < this.settings.frequency; i++)
        {
            if (this.settings.rarity > random.nextDouble() * 100.0)
            {
                int x = chunkMiddleX + random.nextInt(ChunkCoordinate.CHUNK_SIZE);
                int z = chunkMiddleZ + random.nextInt(ChunkCoordinate.CHUNK_SIZE);

                if (spawn(structureCache, worldGenRegion, random, x, z, this.settings.minHeight, this.settings.maxHeight, chunkCoord, this.doReplaceBlocks()))
                {
                    atLeastOneObjectHasSpawned = true;
                }
            }
        }

        return atLeastOneObjectHasSpawned;
    }

    // Used for trees during population
    @Override
    public boolean spawnAsTree(CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, int x, int z, int minY, int maxY, ChunkCoordinate chunkBeingPopulated)
    {
    	// A bit ugly, but avoids having to create and implement another spawnAsTree method.
    	if(minY == -1)
    	{
    		minY = this.getSettings().minHeight;
    	}
    	if(maxY == -1)
    	{
    		maxY = this.getSettings().maxHeight;
    	}
        return spawn(structureCache, worldGenRegion, random, x, z, minY, maxY, chunkBeingPopulated, false);
    }

    // Used for customobject and trees during population
    private boolean spawn(CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, int x, int z, int minY, int maxY, ChunkCoordinate chunkBeingPopulated, boolean replaceBlocks)
    {
        Rotation rotation = this.settings.rotateRandomly ? Rotation.getRandomRotation(random) : Rotation.NORTH;
        int offsetY = 0;
        int baseY = 0;
        if (this.settings.spawnHeight == SpawnHeightEnum.randomY)
        {
        	baseY = minY == maxY ? minY : RandomHelper.numberInRange(random, minY, maxY);
        }
        if (this.settings.spawnHeight == SpawnHeightEnum.highestBlock)
        {
        	baseY = worldGenRegion.getHighestBlockAboveYAt(x, z, chunkBeingPopulated);
        }
        if (this.settings.spawnHeight == SpawnHeightEnum.highestSolidBlock)
        {
        	baseY = worldGenRegion.getBlockAboveSolidHeight(x, z, chunkBeingPopulated);
        }
        // Offset by static and random settings values
        // TODO: This is pointless used with randomY?
        offsetY = baseY + this.getOffsetAndVariance(random, this.settings.spawnHeightOffset, this.settings.spawnHeightVariance);
        return trySpawnAt(null, structureCache, worldGenRegion, random, rotation, x, offsetY, z, minY, maxY, baseY, chunkBeingPopulated, replaceBlocks);
    }
    
    // Used for trees, customobjects and customstructures during population.
    public boolean trySpawnAt(CustomStructure structure, CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, Rotation rotation, int x, int y, int z, int minY, int maxY, int baseY, ChunkCoordinate chunkBeingPopulated, boolean replaceBlocks)
    {
        if (y < Constants.WORLD_DEPTH || y >= Constants.WORLD_HEIGHT) // Isn't this already done before this method is called?
        {
            return false;
        }

        // Height check
        if (y < minY || y > maxY)
        {
            return false;
        }
        
        BO3Check[] checks = this.settings.bo3Checks[rotation.getRotationId()];

        // Check for spawning
        for (BO3Check check : checks)
        {
        	// Don't apply spawn height offset/variance to block checks,
        	// they should only be used with highestBlock/highestSolidBlock,
        	// and need to check for things like grass at the original spawn y.
        	if (check.preventsSpawn(worldGenRegion, x + check.x, baseY + check.y, z + check.z, chunkBeingPopulated))
            {
                // A check failed
                return false;
            }
    	}

        BO3BlockFunction[] blocks = this.settings.getBlocks(rotation.getRotationId());
        HashSet<ChunkCoordinate> loadedChunks = new HashSet<ChunkCoordinate>();
        ChunkCoordinate chunkCoord;
        for (BO3BlockFunction block : blocks)
        {
            if (y + block.y < Constants.WORLD_DEPTH || y + block.y >= Constants.WORLD_HEIGHT)
            {
                return false;
            }

           	chunkCoord = ChunkCoordinate.fromBlockCoords(x + block.x, z + block.z);
        	if(!loadedChunks.contains(chunkCoord))
    		{
        		if(chunkBeingPopulated != null && !ChunkCoordinate.IsInAreaBeingPopulated(x + block.x, z + block.z, chunkBeingPopulated))
        		//if(!world.chunkExists(x + block.x, y + block.y, z + block.z)) 
	            {
                    // Cannot spawn BO3, part of world is not loaded
	                return false;
	            }
	            loadedChunks.add(chunkCoord);
    		}
        }

        ArrayList<BO3BlockFunction> blocksToSpawn = new ArrayList<BO3BlockFunction>();

        ObjectExtrusionHelper oeh = new ObjectExtrusionHelper(this.settings.extrudeMode, this.settings.extrudeThroughBlocks);
        HashSet<ChunkCoordinate> chunks = new HashSet<ChunkCoordinate>();

        int blocksOutsideSourceBlock = 0;
        int maxBlocksOutsideSourceBlock = (int) Math.ceil(
                blocks.length * (this.settings.maxPercentageOutsideSourceBlock / 100.0));
        for (BO3BlockFunction block : blocks)
        {
            if (
        		(
    				(
						this.settings.maxPercentageOutsideSourceBlock < 100 && 
						blocksOutsideSourceBlock <= maxBlocksOutsideSourceBlock
					) || 
    				this.settings.outsideSourceBlock == OutsideSourceBlock.dontPlace
				) && 
    			!this.settings.sourceBlocks.contains(worldGenRegion.getMaterial(x + block.x, y + block.y, z + block.z, chunkBeingPopulated))
    		)
            {
                blocksOutsideSourceBlock++;
                if (blocksOutsideSourceBlock > maxBlocksOutsideSourceBlock)
                {
                    // Too many blocks outside source block
                    return false;
                }

                if (this.settings.outsideSourceBlock == OutsideSourceBlock.placeAnyway)
                {
                    chunks.add(ChunkCoordinate.fromBlockCoords(x + block.x, z + block.z));
                    blocksToSpawn.add(block);
                }
            } else {
                chunks.add(ChunkCoordinate.fromBlockCoords(x + block.x, z + block.z));
                blocksToSpawn.add(block);
            }
            if (block instanceof BO3BlockFunction)
            {
                oeh.addBlock((BO3BlockFunction) block);
            }
        }

        // Call event
        //if (!worldGenRegion.fireCanCustomObjectSpawnEvent(this, x, y, z))
        {
            // Cancelled
            //return false;
        }

        // Spawn

        for (BO3BlockFunction block : blocksToSpawn)
        {
            block.spawn(worldGenRegion, random, x + block.x, y + block.y, z + block.z, chunkBeingPopulated, replaceBlocks);
        }

        oeh.extrude(worldGenRegion, random, x, y, z, chunkBeingPopulated, replaceBlocks);
        handleBO3Functions(structure, structureCache, worldGenRegion, random, rotation, x, y, z, chunks, chunkBeingPopulated);

        return true;
    }

    private void handleBO3Functions(CustomStructure structure, CustomStructureCache structureCache, IWorldGenRegion worldGenRegion, Random random, Rotation rotation, int x, int y, int z, HashSet<ChunkCoordinate> chunks, ChunkCoordinate chunkBeingPopulated)
    {
        HashSet<ChunkCoordinate> chunksCustomObject = new HashSet<ChunkCoordinate>();

        HashSet<BO3ModDataFunction> newModDataInObject = new HashSet<BO3ModDataFunction>();
        BO3ModDataFunction[] modDataInObject = this.settings.modDataFunctions[rotation.getRotationId()];
        for (BO3ModDataFunction modData : modDataInObject)
        {
            BO3ModDataFunction newModData = new BO3ModDataFunction();

            newModData.y = y + modData.y;
            newModData.x = x + modData.x;
            newModData.z = z + modData.z;

            newModData.modData = modData.modData;
            newModData.modId = modData.modId;

            newModDataInObject.add(newModData);
            chunks.add(ChunkCoordinate.fromBlockCoords(newModData.x, newModData.z));
            chunksCustomObject.add(ChunkCoordinate.fromBlockCoords(newModData.x, newModData.z));
        }

        HashSet<BO3SpawnerFunction> newSpawnerDataInObject = new HashSet<BO3SpawnerFunction>();
        BO3SpawnerFunction[] spawnerDataInObject = settings.spawnerFunctions[rotation.getRotationId()];
        for (BO3SpawnerFunction spawnerData : spawnerDataInObject)
        {
            BO3SpawnerFunction newSpawnerData = new BO3SpawnerFunction();

            newSpawnerData.y = y + spawnerData.y;
            newSpawnerData.x = x + spawnerData.x;
            newSpawnerData.z = z + spawnerData.z;

            newSpawnerData.mobName = spawnerData.mobName;
            newSpawnerData.originalnbtFileName = spawnerData.originalnbtFileName;
            newSpawnerData.nbtFileName = spawnerData.nbtFileName;
            newSpawnerData.groupSize = spawnerData.groupSize;
            newSpawnerData.interval = spawnerData.interval;
            newSpawnerData.spawnChance = spawnerData.spawnChance;
            newSpawnerData.maxCount = spawnerData.maxCount;

            newSpawnerData.despawnTime = spawnerData.despawnTime;

            newSpawnerData.velocityX = spawnerData.velocityX;
            newSpawnerData.velocityY = spawnerData.velocityY;
            newSpawnerData.velocityZ = spawnerData.velocityZ;

            newSpawnerData.velocityXSet = spawnerData.velocityXSet;
            newSpawnerData.velocityYSet = spawnerData.velocityYSet;
            newSpawnerData.velocityZSet = spawnerData.velocityZSet;

            newSpawnerData.yaw = spawnerData.yaw;
            newSpawnerData.pitch = spawnerData.pitch;

            newSpawnerDataInObject.add(newSpawnerData);
            chunks.add(ChunkCoordinate.fromBlockCoords(newSpawnerData.x, newSpawnerData.z));
            chunksCustomObject.add(ChunkCoordinate.fromBlockCoords(newSpawnerData.x, newSpawnerData.z));
        }

        HashSet<BO3ParticleFunction> newParticleDataInObject = new HashSet<BO3ParticleFunction>();
        BO3ParticleFunction[] particleDataInObject = this.settings.particleFunctions[rotation.getRotationId()];
        for (BO3ParticleFunction particleData : particleDataInObject)
        {
            BO3ParticleFunction newParticleData = new BO3ParticleFunction();

            newParticleData.y = y + particleData.y;
            newParticleData.x = x + particleData.x;
            newParticleData.z = z + particleData.z;

            newParticleData.particleName = particleData.particleName;

            newParticleData.interval = particleData.interval;

            newParticleData.velocityX = particleData.velocityX;
            newParticleData.velocityY = particleData.velocityY;
            newParticleData.velocityZ = particleData.velocityZ;

            newParticleData.velocityXSet = particleData.velocityXSet;
            newParticleData.velocityYSet = particleData.velocityYSet;
            newParticleData.velocityZSet = particleData.velocityZSet;

            newParticleDataInObject.add(newParticleData);
            chunks.add(ChunkCoordinate.fromBlockCoords(newParticleData.x, newParticleData.z));
            chunksCustomObject.add(ChunkCoordinate.fromBlockCoords(newParticleData.x, newParticleData.z));
        }

        if (structure != null)
        {
            structure.modDataManager.modData.addAll(newModDataInObject);
            structure.particlesManager.particleData.addAll(newParticleDataInObject);
            structure.spawnerManager.spawnerData.addAll(newSpawnerDataInObject);

            for (ChunkCoordinate structureCoord : chunks)
            {
            	structureCache.addBo3ToStructureCache(structureCoord, structure, true);
            }
        } else {
            CustomStructure placeHolderStructure = new BO3CustomStructure(new BO3CustomStructureCoordinate(worldGenRegion.getPresetName(), this, this.getName(), Rotation.NORTH, x, (short) 0, z));
            placeHolderStructure.modDataManager.modData.addAll(newModDataInObject);
            placeHolderStructure.particlesManager.particleData.addAll(newParticleDataInObject);
            placeHolderStructure.spawnerManager.spawnerData.addAll(newSpawnerDataInObject);

            for (ChunkCoordinate structureCoord : chunksCustomObject)
            {
            	structureCache.addBo3ToStructureCache(structureCoord, placeHolderStructure, false);            
            }
        }

        BO3EntityFunction[] entityDataInObject = this.settings.entityFunctions[rotation.getRotationId()];
        for (BO3EntityFunction entity : entityDataInObject)
        {
            BO3EntityFunction newEntityData = new BO3EntityFunction();

            newEntityData.y = y + entity.y;
            newEntityData.x = x + entity.x;
            newEntityData.z = z + entity.z;

            newEntityData.name = entity.name;
            newEntityData.resourceLocation = entity.resourceLocation;
            newEntityData.groupSize = entity.groupSize;
            newEntityData.nameTagOrNBTFileName = entity.nameTagOrNBTFileName;
            newEntityData.originalNameTagOrNBTFileName = entity.originalNameTagOrNBTFileName;
            newEntityData.namedBinaryTag = entity.namedBinaryTag;
            newEntityData.rotation = entity.rotation;

            worldGenRegion.spawnEntity(newEntityData, chunkBeingPopulated);
        }
    }

    public Branch[] getBranches(Rotation rotation)
    {
        return this.settings.branches[rotation.getRotationId()];
    }
    
    public StructurePartSpawnHeight getStructurePartSpawnHeight()
    {
        return this.settings.spawnHeight.toStructurePartSpawnHeight();
    }

    // TODO: Use BoundingBox for BO4's?
    public BoundingBox getBoundingBox(Rotation rotation)
    {
        return this.settings.boundingBoxes[rotation.getRotationId()];
    }

    public int getMaxBranchDepth() // This used to be in CustomObject?
    {
        return this.settings.maxBranchDepth;
    }

    /**
     * Computes the offset and variance for spawning a bo3
     *
     * @param random   Random number generator.
     * @param offset   Base spawn offset.
     * @param variance Max variance from this offset.
     *
     * @return The sum of the offset and variance.
     */
    private int getOffsetAndVariance(Random random, int offset, int variance)
    {
        if (variance == 0)
        {
            return offset;
        } else if (variance < 0)
        {
            variance = -random.nextInt(MathHelper.abs(variance) + 1);
        } else {
            variance = random.nextInt(variance + 1);
        }
        return MathHelper.clamp(offset + variance, Constants.WORLD_DEPTH, Constants.WORLD_HEIGHT - 1);
    }
    
    public CustomStructureCoordinate makeCustomStructureCoordinate(String presetName, Random random, int chunkX, int chunkZ)
    {
        Rotation rotation = this.settings.rotateRandomly ? Rotation.getRandomRotation(random) : Rotation.NORTH;
        int height = RandomHelper.numberInRange(random, this.settings.minHeight, this.settings.maxHeight);
        return new BO3CustomStructureCoordinate(presetName, this, this.getName(), rotation, chunkX * 16 + 8 + random.nextInt(16), (short)height, chunkZ * 16 + 7 + random.nextInt(16));
    }

	@Override
	public boolean doReplaceBlocks()
	{
		return this.settings.doReplaceBlocks;
	}
}
