
package com.jfixby.tool.psd2scene2d;

import com.jfixby.cmns.api.assets.AssetID;
import com.jfixby.cmns.api.collections.Collection;
import com.jfixby.cmns.api.collections.Collections;
import com.jfixby.cmns.api.collections.List;
import com.jfixby.cmns.api.collections.Set;
import com.jfixby.cmns.api.debug.Debug;
import com.jfixby.psd.unpacker.api.PSDLayer;
import com.jfixby.r3.ext.api.scene2d.srlz.SceneStructure;

public class SceneStructurePackingResult {

	List<AssetID> lit = Collections.newList();
	private float scale_factor;
	private final SceneStructure structure;
	private final Set<PSDLayer> ancestors = Collections.newSet();

	public SceneStructurePackingResult (final SceneStructure structure) {
		this.structure = structure;
	}

	public void addRequiredAsset (final AssetID child_scene_asset_id, final List<PSDLayer> list) {
		Debug.checkNull(child_scene_asset_id);
		this.lit.add(child_scene_asset_id);
		this.ancestors.addAll(list);
	}

	public List<AssetID> listRequiredAssets () {
		return this.lit;
	}

	public void setScaleFactor (final float scale_factor) {
		this.scale_factor = scale_factor;
	}

	public float getScaleFactor () {
		return this.scale_factor;
	}

	public Collection<PSDLayer> getAncestors () {
		return this.ancestors;
	}

	public SceneStructure getStructure () {
		return this.structure;
	}

}
