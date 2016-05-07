
package com.jfixby.tool.psd2scene2d;

import java.util.Vector;

import com.jfixby.cmns.api.assets.AssetID;
import com.jfixby.cmns.api.assets.Names;
import com.jfixby.cmns.api.collections.Collections;
import com.jfixby.cmns.api.collections.List;
import com.jfixby.cmns.api.collections.Map;
import com.jfixby.cmns.api.debug.Debug;
import com.jfixby.cmns.api.err.Err;
import com.jfixby.cmns.api.floatn.Float2;
import com.jfixby.cmns.api.geometry.Geometry;
import com.jfixby.cmns.api.log.L;
import com.jfixby.cmns.api.math.FloatMath;
import com.jfixby.psd.unpacker.api.PSDLayer;
import com.jfixby.psd.unpacker.api.PSDRaster;
import com.jfixby.psd.unpacker.api.PSDRasterPosition;
import com.jfixby.r3.api.shader.srlz.SHADER_PARAMETERS;
import com.jfixby.r3.ext.api.scene2d.srlz.Action;
import com.jfixby.r3.ext.api.scene2d.srlz.ActionsGroup;
import com.jfixby.r3.ext.api.scene2d.srlz.Anchor;
import com.jfixby.r3.ext.api.scene2d.srlz.AnimationSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.CameraSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.CameraSettings.MODE;
import com.jfixby.r3.ext.api.scene2d.srlz.ChildSceneSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.InputSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.LayerElement;
import com.jfixby.r3.ext.api.scene2d.srlz.ProgressSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.RASTER_BLEND_MODE;
import com.jfixby.r3.ext.api.scene2d.srlz.Scene2DPackage;
import com.jfixby.r3.ext.api.scene2d.srlz.SceneStructure;
import com.jfixby.r3.ext.api.scene2d.srlz.ShaderParameterType;
import com.jfixby.r3.ext.api.scene2d.srlz.ShaderParameterValue;
import com.jfixby.r3.ext.api.scene2d.srlz.ShaderSettings;
import com.jfixby.r3.ext.api.scene2d.srlz.TextSettings;

public class PSDtoScene2DConverter {

	public static ConversionResult convert (final Scene2DPackage container, final AssetID package_prefix, final PSDLayer root,
		final Map<PSDLayer, AssetID> raster_names) {
		final ConversionResult results = new ConversionResult();

		// naming.print("naming");

		final LayersStack stack = new LayersStack();
		for (int i = 0; i < root.numberOfChildren(); i++) {
			final PSDLayer candidate = root.getChild(i);
			final String candidate_name = candidate.getName();
			if (candidate_name.equalsIgnoreCase(TAGS.R3_SCENE)) {
				final PSDLayer content_layer = candidate.findChildByNamePrefix(TAGS.CONTENT);
				if (content_layer == null) {
					continue;
				}
				final PSDLayer name_layer = candidate.findChildByNamePrefix(TAGS.STRUCTURE_NAME);
				if (name_layer == null) {
					Err.reportError("missing NAME tag");
					continue;
				}

				final PSDLayer camera_layer = candidate.findChildByNamePrefix(TAGS.CAMERA);

				float scale_factor = 1f;
				{
					final PSDLayer divisor = candidate.findChildByNamePrefix(TAGS.SCALE_DIVISOR);
					if (divisor != null) {
						final String divisor_string = readParameter(divisor, TAGS.SCALE_DIVISOR);
						scale_factor = 1f / Float.parseFloat(divisor_string);
					}
				}
				final SceneStructure structure = new SceneStructure();
				final ConvertionSettings settings = new ConvertionSettings(structure);
				structure.root = settings.newLayerElement();
				settings.setScaleFactor(scale_factor);

				final SceneStructurePackingResult result_i = new SceneStructurePackingResult(structure);

				settings.setResult(result_i);
				result_i.setScaleFactor(scale_factor);

				container.structures.addElement(structure);
				structure.structure_name = readParameter(name_layer.getName(), TAGS.STRUCTURE_NAME);
				structure.structure_name = package_prefix.child(structure.structure_name).toString();
				final LayerElement element = structure.root;

				final PsdRepackerNameResolver naming = new PsdRepackerNameResolver(Names.newAssetID(structure.structure_name),
					raster_names);
				settings.setNaming(naming);
				convert(stack, content_layer, element, settings);

				element.name = structure.structure_name;

				setupCamera(stack, camera_layer, element, scale_factor);

				structure.original_width = element.camera_settings.width;
				structure.original_height = element.camera_settings.height;

				L.d("structure found", structure.structure_name);

				results.putResult(structure, result_i);
			}
		}

		return results;
	}

	private static void setupCamera (final LayersStack stack, final PSDLayer camera_layer, final LayerElement element,
		final double scale_factor) {
		if (camera_layer == null) {
			return;
		}
		final CameraSettings cameraSettings = new CameraSettings();

		final PSDLayer area = camera_layer.findChildByNamePrefix(TAGS.AREA);
		final PSDLayer mode = camera_layer.findChildByNamePrefix(TAGS.MODE);
		if (area == null) {
			stack.print();
			throw new Error("Tag <" + TAGS.AREA + "> not found.");
		}

		if (area != null) {
			final PSDRaster raster = area.getRaster();
			Debug.checkNull("raster", raster);

			cameraSettings.position_x = raster.getPosition().getX() * scale_factor;
			cameraSettings.position_y = raster.getPosition().getY() * scale_factor;
			cameraSettings.width = raster.getDimentions().getWidth() * scale_factor;
			cameraSettings.height = raster.getDimentions().getHeight() * scale_factor;

		}

		if (mode != null) {
			final String modeString = readParameter(mode, TAGS.MODE);
			cameraSettings.mode = MODE.valueOf(modeString.toUpperCase());
		}

		element.camera_settings = cameraSettings;

	}

	private static void convert (final LayersStack stack, final PSDLayer input, final LayerElement output,
		final ConvertionSettings settings) {

		if (input.isFolder()) {
			stack.push(input);
			final PSDLayer animation_node = input.findChildByNamePrefix(TAGS.ANIMATION);
			final PSDLayer childscene_node = input.findChildByNamePrefix(TAGS.CHILD_SCENE);
			final PSDLayer text_node = input.findChildByNamePrefix(TAGS.R3_TEXT);
			final PSDLayer shader_node = input.findChildByNamePrefix(TAGS.R3_SHADER);
			final PSDLayer user_input = input.findChildByNamePrefix(TAGS.INPUT);
			final PSDLayer progress = input.findChildByNamePrefix(TAGS.PROGRESS);
			// PSDLayer events_node = input.findChild(EVENT);
			if (animation_node != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an animation node: " + input);
				}
				convertAnimation(stack, input, output, settings);
			} else if (childscene_node != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an child scene node: " + input);
				}
				convertChildScene(input, output, settings);
			} else if (shader_node != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an	 child scene node: " + input);
				}
				convertShader(input, output, settings);
			} else if (text_node != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an child scene node: " + input);
				}
				convertText(stack, input, output, settings);
			} else if (user_input != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an child scene node: " + input);
				}
				convertInput(stack, input, output, settings);
			} else if (progress != null) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an child scene node: " + input);
				}
				convertProgress(stack, input, output, settings);
			} else if (false) {
				if (input.numberOfChildren() != 1) {
					throw new Error("Annotation problem (only one child allowed). This is not an child scene node: " + input);
				}
				// convertEventsSequence(input, output, naming, result,
				// scale_factor);
			} else {
				convertFolder(stack, input, output, settings);
			}
			stack.pop(input);
		} else if (input.isRaster()) {
			convertRaster(input, output, settings);
		}

	}

	private static void convertShader (final PSDLayer input, final LayerElement output, final ConvertionSettings settings) {

		final PSDLayer shader_node = input.findChildByNamePrefix(TAGS.R3_SHADER);
		ShaderSettings shader_settings = null;
		Debug.checkNull("shader_node", shader_node);

		shader_settings = new ShaderSettings();

		output.is_hidden = !input.isVisible();
		output.is_shader = true;
		output.shader_settings = shader_settings;

		{
			final PSDLayer id_layer = findChild(TAGS.ID, shader_node);
			if (id_layer == null) {
				throw new Error("Missing tag <" + TAGS.ID + ">");
			} else {
				final String id_string = readParameter(id_layer, TAGS.ID);
				output.shader_id = id_string;
				output.name = input.getName();
				final SceneStructurePackingResult result = settings.getResult();
				result.addRequiredAsset(Names.newAssetID(id_string), Collections.newList(shader_node));
			}
		}

		{
			final double scale_factor = settings.getScaleFactor();
			final PSDLayer origin = shader_node.findChildByNamePrefix(TAGS.ORIGIN);
			if (origin != null) {
				final double shader_x = origin.getRaster().getPosition().getX() * scale_factor;
				final double shader_y = origin.getRaster().getPosition().getY() * scale_factor;
				final ShaderParameterValue canvas_x = new ShaderParameterValue(SHADER_PARAMETERS.POSITION_X, "" + shader_x,
					ShaderParameterType.FLOAT);
				final ShaderParameterValue canvas_y = new ShaderParameterValue(SHADER_PARAMETERS.POSITION_Y, "" + shader_y,
					ShaderParameterType.FLOAT);

				shader_settings.params.addElement(canvas_x);
				shader_settings.params.addElement(canvas_y);

				final PSDLayer radius = shader_node.findChildByNamePrefix(TAGS.RADIUS);
				if (radius != null) {
					final double rx = radius.getRaster().getPosition().getX() * scale_factor;
					final double ry = radius.getRaster().getPosition().getY() * scale_factor;
					final double shader_radius = FloatMath.distance(shader_x, shader_y, rx, ry);

					final ShaderParameterValue radius_p = new ShaderParameterValue(SHADER_PARAMETERS.RADIUS, "" + shader_radius,
						ShaderParameterType.FLOAT);

					shader_settings.params.addElement(radius_p);

				} else {
					Err.reportError("Shader radius not found: " + shader_node);
				}
			}

		}

	}

	private static String readParameter (final PSDLayer layer, final String id) {
		String id_string = readParameter(layer.getName(), id);
		if (id_string.length() > 0) {
			return id_string;
		}
		PSDLayer next = layer;
		id_string = "";
		do {
			next = next.getChild(0);
			id_string = id_string + next.getName();
			if (next.numberOfChildren() > 0) {
				id_string = id_string + ".";
			}
		} while (next.numberOfChildren() > 0);
		return id_string;
	}

	private static void convertChildScene (final PSDLayer input_parent, final LayerElement output,
		final ConvertionSettings settings) {

		final String name = input_parent.getName();
		output.is_hidden = !input_parent.isVisible();
		output.is_child_scene = true;
		output.name = name;

		output.child_scene_settings = new ChildSceneSettings();

		final PSDLayer input = input_parent.findChildByNamePrefix(TAGS.CHILD_SCENE);

		final PSDLayer frame = input.findChildByNamePrefix(TAGS.FRAME);
		{
			if (frame != null) {
				throw new Error("Unsupported tag: " + TAGS.FRAME);
			}
		}

		final PSDLayer origin = input.findChildByNamePrefix(TAGS.ORIGIN);
		if (origin != null) {

			final double scale_factor = settings.getScaleFactor();
			output.child_scene_settings.frame_position_x = origin.getRaster().getPosition().getX() * scale_factor;
			output.child_scene_settings.frame_position_y = origin.getRaster().getPosition().getY() * scale_factor;

			output.child_scene_settings.frame_width = origin.getRaster().getDimentions().getWidth();
			output.child_scene_settings.frame_height = origin.getRaster().getDimentions().getHeight();
		}
		{
			final PSDLayer id = findChild(TAGS.ID, input);

			if (id == null) {
				throw new Error("Missing tag <@" + TAGS.ID + ">");
			} else {
				final String child_id = readParameter(id, TAGS.ID);

				final AssetID child_scene_asset_id = Names.newAssetID(child_id);

				output.child_scene_settings.child_scene_id = child_scene_asset_id.toString();

				// L.e("!!!!!!");
				final SceneStructurePackingResult result = settings.getResult();
				result.addRequiredAsset(child_scene_asset_id, Collections.newList(input_parent, input, origin));
			}
		}

	}

	private static void convertText (final LayersStack stack, final PSDLayer input_parent, final LayerElement output,
		final ConvertionSettings settings) {
		final SceneStructure structure = settings.getStructure();
		final String name = input_parent.getName();
		output.is_hidden = !input_parent.isVisible();
		output.is_text = true;
		output.name = name;

		output.text_settings = new TextSettings();

		final PSDLayer input = input_parent.findChildByNamePrefix(TAGS.R3_TEXT);

		final PSDLayer frame = input.findChildByNamePrefix(TAGS.FRAME);
		{
			if (frame != null) {
				throw new Error("Unsupported tag: " + TAGS.FRAME);
			}
		}
		{
			final double scale_factor = settings.getScaleFactor();
			final PSDLayer background = input.findChildByNamePrefix(TAGS.BACKGROUND);
			if (background != null) {
				final PSDLayer child = background.getChild(0);
				final LayerElement raster_element = settings.newLayerElement();
				convertRaster(child, raster_element, settings);
				output.children.addElement(raster_element, structure);

				raster_element.position_x = 0;
				raster_element.position_y = 0;

				final PSDRaster raster = child.getRaster();
				output.position_x = raster.getPosition().getX() * scale_factor;
				output.position_y = raster.getPosition().getY() * scale_factor;

				// String text_value_asset_id_string =
				// readParameter(id.getName(), TAGS.ID);
				// AssetID text_value_asset_id =
				// naming.childText(text_value_asset_id_string);
				// output.text_settings.text_value_asset_id =
				// text_value_asset_id.toString();
				// result.addRequiredAsset(text_value_asset_id,
				// JUtils.newList(input));
			}
		}
		{
			final PSDLayer id = findChild(TAGS.ID, input);
			if (id == null) {
				throw new Error("Missing tag <" + TAGS.ID + ">");
			} else {
				final String bar_id_string = readParameter(id, TAGS.ID);
				final PsdRepackerNameResolver naming = settings.getNaming();
				final AssetID bar_id = naming.childText(bar_id_string);
				output.textbar_id = bar_id.toString();
			}
		}
		{
			final PSDLayer text_node = input.findChildByNamePrefix(TAGS.TEXT);
			if (text_node != null) {
// final PSDLayer id = findChild(TAGS.ID, text_node);
// if (id == null) {
// throw new Error("Missing tag <@" + TAGS.ID + ">");
// } else {
// stack.print();
// throw new Error("Missing tag <@" + TAGS.ID + ">");
// final String text_value_asset_id_string = readParameter(id.getName(), TAGS.ID);
// final AssetID text_value_asset_id = naming.childText(text_value_asset_id_string);
// output.text_settings.text_value_asset_id = text_value_asset_id.toString();
// result.addRequiredAsset(text_value_asset_id, Collections.newList(input));
// }
// AssetID child_scene_asset_id = null;
// result.addRequiredRaster(child_scene_asset_id,
// JUtils.newList(input_parent, input, background));
			}
		}
		final double scale_factor = settings.getScaleFactor();
		{
			final PSDLayer font_node = input.findChildByNamePrefix(TAGS.FONT);
			if (font_node != null) {
				final PSDLayer size = findChild(TAGS.SIZE, font_node);
				if (size == null) {
					throw new Error("Missing tag <@" + TAGS.SIZE + ">");
				} else {
					final String font_size_string = readParameter(size.getName(), TAGS.SIZE);
					output.text_settings.font_settings.font_size = (Float.parseFloat(font_size_string));
					output.text_settings.font_settings.font_scale = (float)scale_factor;
					output.text_settings.font_settings.value_is_in_pixels = true;
				}
				// AssetID child_scene_asset_id = null;
				// result.addRequiredRaster(child_scene_asset_id,
				// JUtils.newList(input_parent, input, background));
			}
			final PSDLayer font_name = font_node.findChildByNamePrefix(TAGS.NAME);
			if (font_name != null) {
				final String font_name_string = readParameter(font_name.getName(), TAGS.NAME);
				output.text_settings.font_settings.name = font_name_string;
				final SceneStructurePackingResult result = settings.getResult();
				result.addRequiredAsset(Names.newAssetID(font_name_string), Collections.newList(input));
			}
			final PSDLayer padding = input.findChildByNamePrefix(TAGS.PADDING);
			if (padding != null) {
				String padding_string = readParameter(padding.getName(), TAGS.PADDING);
				padding_string = padding_string.substring(0, padding_string.indexOf("pix"));
				output.text_settings.padding = (float)(Float.parseFloat(padding_string) * scale_factor);
			}
		}
	}

	private static void convertProgress (final LayersStack stack, final PSDLayer input_parent, final LayerElement output,
		final ConvertionSettings settings) {

		final String name = input_parent.getName();
		output.is_hidden = !input_parent.isVisible();
		output.is_progress = true;
		output.name = name;

		final PSDLayer progress = input_parent.findChildByNamePrefix(TAGS.PROGRESS);

		output.progress_settings = new ProgressSettings();
		{
			final PSDLayer type = progress.findChildByNamePrefix(TAGS.TYPE);

			if (type == null) {
				stack.print();
				input_parent.printChildren();
				throw new Error("Missing tag <" + TAGS.TYPE + ">");
			} else {
				final String typevalue = readParameter(type, TAGS.TYPE).toUpperCase();
				output.progress_settings.type = ProgressSettings.TYPE.valueOf(typevalue);
			}
		}
		{
			final SceneStructure structure = settings.getStructure();
			final PSDLayer raster = progress.findChildByNamePrefix(TAGS.RASTER);

			if (raster == null) {
				stack.print();
				throw new Error("Missing tag <@" + TAGS.RASTER + ">");
			} else {
				final LayerElement rasterNode = settings.newLayerElement();
				convert(stack, raster.getChild(0), rasterNode, settings);
				output.children.addElement(rasterNode, structure);
			}
		}

	}

	private static void convertFolder (final LayersStack stack, final PSDLayer input, final LayerElement coutput,
		final ConvertionSettings settings) {

		{
			final LayerElement output = coutput;
			// output.shader_settings = shader_settings;
			output.is_hidden = !input.isVisible();
			output.name = input.getName();

			output.is_sublayer = true;

			for (int i = 0; i < input.numberOfChildren(); i++) {
				final PSDLayer child = input.getChild(i);
				// if (shader_node != null && shader_node == child) {
				// continue;
				// }
				final LayerElement element = settings.newLayerElement();
				;
				final SceneStructure structure = settings.getStructure();
				output.children.addElement(element, structure);
				convert(stack, child, element, settings);

				if (element.name.startsWith("@")) {
					stack.print();
					throw new Error("Bad layer name: " + element.name);
				}
			}
		}
	}

	private static void convertInput (final LayersStack stack, final PSDLayer input_parent, final LayerElement output,
		final ConvertionSettings settings) {

		final String name = input_parent.getName();
		output.is_hidden = !input_parent.isVisible();
		output.is_user_input = true;
		output.name = name;

		final PSDLayer input = input_parent.findChildByNamePrefix(TAGS.INPUT);

		final InputSettings input_settings = new InputSettings();
		output.input_settings = input_settings;

		{
			final PSDLayer debug = findChild(TAGS.DEBUG, input);

			if (debug == null) {
				output.debug_mode = false;
			} else {
				final String debug_mode = readParameter(debug, TAGS.DEBUG);
				output.debug_mode = Boolean.parseBoolean(debug_mode);
			}
		}

		final double scale_factor = settings.getScaleFactor();
		final PSDLayer origin_layer = findChild(TAGS.ORIGIN, input);
		final Float2 origin = Geometry.newFloat2();
		if (origin_layer != null) {
			final PSDRaster raster = origin_layer.getChild(0).getRaster();
			origin.setXY(raster.getPosition().getX() * scale_factor, raster.getPosition().getY() * scale_factor);
		}

		output.position_x = origin.getX();
		output.position_y = origin.getY();
// PSDLayer type = findChild(ANIMATION_TYPE, input);
		{
			final PSDLayer type = findChild(TAGS.TYPE, input);
			if (type == null) {

			} else {
				final String type_value = readParameter(type.getName(), TAGS.TYPE);

				output.input_settings.is_button = TAGS.VALUE_BUTTON.equalsIgnoreCase(type_value);
				output.input_settings.is_switch = TAGS.VALUE_SWITCH.equalsIgnoreCase(type_value);
				output.input_settings.is_custom = TAGS.VALUE_CUSTOM.equalsIgnoreCase(type_value);
				output.input_settings.is_touch_area = TAGS.VALUE_TOUCH.equalsIgnoreCase(type_value);

				// animation_settings.is_positions_modifyer_animation =
				// ANIMATION_TYPE_POSITION_MODIFIER
				// c;

			}

			final PSDLayer raster = findChild(TAGS.RASTER, input);
			if (output.input_settings.is_button) {
				extractButtonRaster(stack, raster, output, settings, origin);
			} else if (output.input_settings.is_switch) {
				extractButtonOptions(stack, raster, output, settings, origin);
			} else if (output.input_settings.is_custom) {
				extractButtonOptions(stack, raster, output, settings, origin);
			} else if (output.input_settings.is_touch_area) {
				L.d(output.input_settings);
// final PSDLayer area = findChild(TAGS.AREA, input);
// final PSDLayer dimentions = area.getChild(0);
// extractTouchArea(stack, dimentions, output, settings, origin);
			} else {
				stack.print();
				Err.reportError("Unknown input type: " + type);
			}

		}

		{
			final PSDLayer touch_area = findChild(TAGS.AREA, input);
			// output.input_settings.areas = new Vector<TouchArea>();
			if (touch_area != null) {
				final LayerElement touch_areas = settings.newLayerElement();
				;
				output.input_settings.touch_area = touch_areas;

				for (int i = 0; i < touch_area.numberOfChildren(); i++) {
					final PSDLayer child = touch_area.getChild(i);
					if (child.isFolder()) {
						throw new Error("Touch area has no dimentions: " + child);
					} else {
						final PSDRaster raster = child.getRaster();
						Debug.checkNull("raster", raster);

						final LayerElement area = settings.newLayerElement();
						;
						final SceneStructure structure = settings.getStructure();
						touch_areas.children.addElement(area, structure);
						area.position_x = raster.getPosition().getX() * scale_factor - origin.getX();
						area.position_y = raster.getPosition().getY() * scale_factor - origin.getY();
						area.width = raster.getDimentions().getWidth() * scale_factor;
						area.height = raster.getDimentions().getHeight() * scale_factor;
						area.name = child.getName();

						// TouchArea area = new TouchArea();
						// area.position_x = raster.getPosition().getX();
						// area.position_y = raster.getPosition().getY();
						// area.width = raster.getDimentions().getWidth();
						// area.height = raster.getDimentions().getHeight();
						//
						// output.input_settings.areas.add(area);
					}

				}
			}

		}

	}

	private static void extractButtonOptions (final LayersStack stack, final PSDLayer options, final LayerElement output,
		final ConvertionSettings settings, final Float2 origin) {
		if (options == null) {
			return;
		}
		for (int i = 0; i < options.numberOfChildren(); i++) {
			final PSDLayer child = options.getChild(i);
			final LayerElement converted = settings.newLayerElement();
			;

			convert(stack, child, converted, settings);
			if (!converted.is_raster) {
				stack.print();
				Err.reportError(converted + "");
			}
			final SceneStructure structure = settings.getStructure();
			output.children.addElement(converted, structure);
			converted.position_x = converted.position_x - origin.getX();
			converted.position_y = converted.position_y - origin.getY();
		}

	}

	private static void extractTouchArea (final LayersStack stack, final PSDLayer area, final LayerElement output,
		final ConvertionSettings settings, final Float2 origin) {

		final LayerElement converted = settings.newLayerElement();

		convert(stack, area, converted, settings);
		if (!converted.is_raster) {
			stack.print();
			Err.reportError(converted + "");
		}
		final SceneStructure structure = settings.getStructure();
		output.children.addElement(converted, structure);
		converted.position_x = converted.position_x - origin.getX();
		converted.position_y = converted.position_y - origin.getY();

	}

	private static void extractButtonRaster (final LayersStack stack, final PSDLayer raster, final LayerElement output,
		final ConvertionSettings settings, final Float2 origin) {

		{
			final PSDLayer on_released = raster.findChildByName(TAGS.BUTTON_ON_RELEASED);
			if (on_released != null) {
				final LayerElement converted = settings.newLayerElement();
				;
				convert(stack, on_released, converted, settings);
				output.input_settings.on_released = converted;
				converted.position_x = converted.position_x - origin.getX();
				converted.position_y = converted.position_y - origin.getY();
			}
		}

		{
			final PSDLayer on_hover = raster.findChildByName(TAGS.BUTTON_ON_HOVER);
			if (on_hover != null) {
				final LayerElement converted = settings.newLayerElement();
				;
				convert(stack, on_hover, converted, settings);
				output.input_settings.on_hover = converted;
				converted.position_x = converted.position_x - origin.getX();
				converted.position_y = converted.position_y - origin.getY();
			}
		}

		{
			final PSDLayer on_press = raster.findChildByName(TAGS.BUTTON_ON_PRESS);
			if (on_press != null) {
				final LayerElement converted = settings.newLayerElement();
				;
				convert(stack, on_press, converted, settings);
				output.input_settings.on_press = converted;
				converted.position_x = converted.position_x - origin.getX();
				converted.position_y = converted.position_y - origin.getY();
			}
		}

		{
			final PSDLayer on_pressed = raster.findChildByName(TAGS.BUTTON_ON_PRESSED);
			if (on_pressed != null) {
				final LayerElement converted = settings.newLayerElement();
				;
				convert(stack, on_pressed, converted, settings);
				output.input_settings.on_pressed = converted;
				converted.position_x = converted.position_x - origin.getX();
				converted.position_y = converted.position_y - origin.getY();
			}
		}

		{
			final PSDLayer on_release = raster.findChildByName(TAGS.BUTTON_ON_RELEASE);
			if (on_release != null) {
				final LayerElement converted = settings.newLayerElement();
				;
				convert(stack, on_release, converted, settings);
				output.input_settings.on_release = converted;
				converted.position_x = converted.position_x - origin.getX();
				converted.position_y = converted.position_y - origin.getY();
			}
		}

	}

	private static void convertAnimation (final LayersStack stack, final PSDLayer input_parent, final LayerElement output,

		final ConvertionSettings settings) {

		final SceneStructure structure = settings.getStructure();

		final String name = input_parent.getName();
		output.is_hidden = !input_parent.isVisible();
		output.is_animation = true;
		output.name = name;

		final PSDLayer input = input_parent.findChildByNamePrefix(TAGS.ANIMATION);

		final AnimationSettings animation_settings = new AnimationSettings();
		output.animation_settings = animation_settings;

		{
			final PSDLayer looped = findChild(TAGS.IS_LOOPED, input);

			if (looped == null) {
				animation_settings.is_looped = true;
			} else {
				final String is_looped = readParameter(looped, TAGS.IS_LOOPED);
				animation_settings.is_looped = Boolean.parseBoolean(is_looped);
			}
		}

		{
			final PSDLayer debug = findChild(TAGS.DEBUG, input);

			if (debug == null) {
				output.debug_mode = false;
			} else {
				final String debug_mode = readParameter(debug.getName(), TAGS.DEBUG);
				output.debug_mode = Boolean.parseBoolean(debug_mode);
			}
		}

		{
			final PSDLayer autostart = findChild(TAGS.AUTOSTART, input);

			if (autostart == null) {
				animation_settings.autostart = false;
			} else {
				final String autostart_string = readParameter(autostart.getName(), TAGS.AUTOSTART);
				animation_settings.autostart = Boolean.parseBoolean(autostart_string);
			}
		}

		{
			final PSDLayer id = findChild(TAGS.ID, input);

			if (id == null) {
				throw new Error("Animation ID tag not found: " + input);
			} else {
				output.animation_id = readParameter(id.getName(), TAGS.ID);
				final PsdRepackerNameResolver naming = settings.getNaming();
				output.animation_id = naming.childAnimation(output.animation_id).toString();
			}
		}
		// PSDLayer type = findChild(ANIMATION_TYPE, input);
		{
			final PSDLayer type = findChild(TAGS.TYPE, input);
			if (type == null) {
				animation_settings.is_positions_modifyer_animation = false;
				animation_settings.is_simple_animation = true;
			} else {
				final String type_value = readParameter(type.getName(), TAGS.TYPE);
				animation_settings.is_positions_modifyer_animation = TAGS.ANIMATION_TYPE_POSITION_MODIFIER
					.equalsIgnoreCase(type_value);

			}

			if (!(animation_settings.is_positions_modifyer_animation || animation_settings.is_simple_animation)) {
				throw new Error("Unknown animation type: " + type);
			}
		}

		if (animation_settings.is_simple_animation) {
			{
				final PSDLayer frames = findChild(TAGS.ANIMATION_FRAMES, input);
				if (frames == null) {
					L.d("Missing <frames> folder in node: " + input);
				}
				Debug.checkNull("frames", frames);
				for (int i = 0; i < frames.numberOfChildren(); i++) {
					final PSDLayer child = frames.getChild(i);
					final LayerElement element = settings.newLayerElement();
					;
					output.children.addElement(element, structure);
					convert(stack, child, element, settings);
				}
				if (frames.numberOfChildren() == 0) {
					throw new Error("No frames found for " + output.animation_id);
				}
			}
			{
				final PSDLayer frame = findChild(TAGS.FRAME_TIME, input);
				if (frame == null) {
					// animation_settings.single_frame_time = Long.MAX_VALUE;
					throw new Error("Missing frame time tag: @" + TAGS.FRAME_TIME);

				} else {
					final String type_value = readParameter(frame.getName(), TAGS.FRAME_TIME);
					animation_settings.single_frame_time = "" + Long.parseLong(type_value);
				}
			}
			return;
		}

		if (animation_settings.is_positions_modifyer_animation) {
			final PSDLayer anchors = findChild(TAGS.ANIMATION_ANCHORS, input);
			Debug.checkNull("frames", anchors);
			animation_settings.anchors = new Vector<Anchor>();
			final double scale_factor = settings.getScaleFactor();
			for (int i = 0; i < anchors.numberOfChildren(); i++) {
				final PSDLayer anchor_layer = anchors.getChild(i);
				final String anchor_time_string = anchor_layer.getName();
				final PSDRasterPosition position = anchor_layer.getRaster().getPosition();
				final Anchor anchor = new Anchor();

				anchor.time = "" + getTime(anchor_time_string);
				anchor.position_x = position.getX() * scale_factor;
				anchor.position_y = position.getY() * scale_factor;
				animation_settings.anchors.add(anchor);
			}

			final PSDLayer scene = findChild(TAGS.ANIMATION_SCENE, input);
			final PSDLayer origin_layer = findChild(TAGS.ORIGIN, scene);
			final Float2 origin = Geometry.newFloat2();
			if (origin_layer != null) {
				final PSDRaster raster = origin_layer.getRaster();
				origin.setXY(raster.getPosition().getX() * scale_factor, raster.getPosition().getY() * scale_factor);
			}
			{
				// LayerElement element = new LayerElement();
				// output.children.addElement(element);
				// convert(scene, element, naming, result);

				for (int i = 0; i < scene.numberOfChildren(); i++) {
					final PSDLayer child = scene.getChild(i);
					Debug.checkNull("child", child);
					if (child == origin_layer) {
						continue;
					}
					final LayerElement element = settings.newLayerElement();
					;
					output.children.addElement(element, structure);
					convert(stack, child, element, settings);
					element.position_x = element.position_x - origin.getX();
					element.position_y = element.position_y - origin.getY();

				}
			}

			return;
		}

	}

	private static void packAnimationEvents (final PSDLayer events_list, final ActionsGroup e_list,
		final ChildAssetsNameResolver naming) {
		e_list.actions = new Vector<Action>();
		for (int i = 0; i < events_list.numberOfChildren(); i++) {
			final PSDLayer element = events_list.getChild(i);
			String event_id = readParameter(element, TAGS.ID);

			event_id = naming.childEvent(event_id).toString();

			final Action event = new Action();
			event.animation_id = event_id;
			event.is_start_animation = true;
			e_list.actions.addElement(event);

		}
	}

	private static long getTime (final String anchor_time_string) {
		final List<String> list = Collections.newList(anchor_time_string.split(":"));
		list.reverse();

		final long frame = Long.parseLong(list.getElementAt(0));
		final long second = Long.parseLong(list.getElementAt(1));
		long min = 0;
		if (list.size() > 2) {
			min = Long.parseLong(list.getElementAt(2));
		}
		final long ms = frame * 1000 / 30;

		return min * 60 * 1000 + second * 1000 + ms;
	}

	private static String readParameter (final String raw_value, final String prefix) {

		Debug.checkEmpty("raw_value", raw_value);
		Debug.checkEmpty("prefix", prefix);

		Debug.checkNull("raw_value", raw_value);
		Debug.checkNull("prefix", prefix);

		return raw_value.substring(prefix.length(), raw_value.length());
	}

	private static PSDLayer findChild (final String name_perefix, final PSDLayer input) {
		for (int i = 0; i < input.numberOfChildren(); i++) {
			final PSDLayer child = input.getChild(i);
			if (child.getName().startsWith(name_perefix)) {
				return child;
			}
		}
		return null;
	}

	private static void convertRaster (final PSDLayer input, final LayerElement output, final ConvertionSettings settings) {
		final PSDRasterPosition position = input.getRaster().getPosition();
		output.is_hidden = !input.isVisible();
		output.name = input.getName();

		if (output.name.startsWith("@")) {
			throw new Error("Bad layer name: " + output.name);
		}

		// if (input.getName().startsWith("area_touch1")) {
		// L.d();
		// }
		final double scale_factor = settings.getScaleFactor();
		output.is_raster = true;
		output.blend_mode = RASTER_BLEND_MODE.valueOf(input.getMode().toString());
		output.position_x = position.getX() * scale_factor;
		output.position_y = position.getY() * scale_factor;
		output.width = position.getWidth() * scale_factor;
		output.opacity = input.getOpacity();
		output.height = position.getHeight() * scale_factor;
		final PsdRepackerNameResolver naming = settings.getNaming();
		final SceneStructurePackingResult result = settings.getResult();
		final String raster_name = naming.getPSDLayerName(input).toString();
		output.raster_id = raster_name;
		result.addRequiredAsset(Names.newAssetID(output.raster_id), Collections.newList(input));
	}

}
