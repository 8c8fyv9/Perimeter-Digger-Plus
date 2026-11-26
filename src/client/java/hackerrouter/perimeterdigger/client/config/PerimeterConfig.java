/*
 * This file is part of the Perimeter Digger project, licensed under the
 * GNU General Public License v3.0 or later.
 *
 * Copyright (C) 2026  HackerRouter
 *
 * Perimeter Digger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Perimeter Digger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Perimeter Digger. If not, see <https://www.gnu.org/licenses/>.
 */

package hackerrouter.perimeterdigger.client.config;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PerimeterConfig {
	public int schemaVersion = ConfigMigration.CURRENT_SCHEMA_VERSION;
	public Integer diggingMinY;
	public Integer diggingMaxY;
	public PositionConfig consumableSupplyPoint;
	public PositionConfig durabilitySupplyPoint;
	public PositionConfig bedPoint;
	public PositionConfig perimeterPortalOverworld;
	public PositionConfig perimeterPortalNether;
	public PositionConfig repairPortalOverworld;
	public PositionConfig repairPortalNether;
	public PositionConfig furnaceRowStart;
	public PositionConfig furnaceRowEnd;
	public Map<String, UnloadingPointConfig> unloadingPoints = new LinkedHashMap<>();
	public Map<String, PositionConfig> schematicResourcePoints = new LinkedHashMap<>();
	public String liquidPolicy = "seal_boundary";
	public String miningStrategy = "global_top_down";
	public String durabilityRecoveryMode = "repair_portal";
	public String workMode = "mine";
	public String schematicWrongBlockPolicy = "break_and_place";
	public String schematicMissingMaterialPolicy = "place_available";
	public boolean schematicClearExtraBlocks = true;
	public boolean schematicOptimizeWater = true;
	public String bubbleColumnEscapeMode = "auto";
	public List<String> sealingBlocks = new ArrayList<>(List.of("minecraft:netherrack"));
	public List<String> scaffoldBlocks = new ArrayList<>();
	public List<String> foods = new ArrayList<>(List.of("minecraft:enchanted_golden_apple"));
	public List<String> unloadingWhitelist = new ArrayList<>();
	public List<String> miningWhitelist = new ArrayList<>();
	public List<String> miningBlacklist = new ArrayList<>();
	public DetectedAreaConfig detectedArea;
	public FunctionConfig functions = new FunctionConfig();
	public AdvancedConfig advanced = new AdvancedConfig();

	public void normalize() {
		schemaVersion = ConfigMigration.CURRENT_SCHEMA_VERSION;
		if (unloadingPoints == null) {
			unloadingPoints = new LinkedHashMap<>();
		}
		if (schematicResourcePoints == null) {
			schematicResourcePoints = new LinkedHashMap<>();
		}
		if (detectedArea != null && detectedArea.scanlines == null) {
			detectedArea.scanlines = new java.util.ArrayList<>();
		}
		if (liquidPolicy == null) {
			liquidPolicy = "seal_boundary";
		}
		if (miningStrategy == null) {
			miningStrategy = "global_top_down";
		}
		if (durabilityRecoveryMode == null) {
			durabilityRecoveryMode = "repair_portal";
		}
		if (workMode == null || workMode.isBlank()) {
			workMode = "mine";
		}
		if (schematicWrongBlockPolicy == null || schematicWrongBlockPolicy.isBlank()) {
			schematicWrongBlockPolicy = "break_and_place";
		}
		if (schematicMissingMaterialPolicy == null || schematicMissingMaterialPolicy.isBlank()) {
			schematicMissingMaterialPolicy = "place_available";
		}
		if (bubbleColumnEscapeMode == null || bubbleColumnEscapeMode.isBlank()) {
			bubbleColumnEscapeMode = "auto";
		}
		if (sealingBlocks == null) {
			sealingBlocks = new ArrayList<>(List.of("minecraft:netherrack"));
		}
		if (scaffoldBlocks == null) {
			scaffoldBlocks = new ArrayList<>();
		}
		if (foods == null) {
			foods = new ArrayList<>(List.of("minecraft:enchanted_golden_apple"));
		}
		if (unloadingWhitelist == null) {
			unloadingWhitelist = new ArrayList<>();
		}
		if (miningWhitelist == null) {
			miningWhitelist = new ArrayList<>();
		}
		if (miningBlacklist == null) {
			miningBlacklist = new ArrayList<>();
		}
		if (functions == null) {
			functions = new FunctionConfig();
		}
		if (advanced == null) {
			advanced = new AdvancedConfig();
		}
	}
}
