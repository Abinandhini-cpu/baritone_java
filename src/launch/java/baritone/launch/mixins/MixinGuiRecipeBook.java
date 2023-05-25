/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.utils.accessor.IMixinGuiRecipeBook;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GuiRecipeBook.class)
public class MixinGuiRecipeBook implements IMixinGuiRecipeBook {

    @Shadow
    private GuiTextField searchBar;

    @Shadow
    private RecipeBookPage recipeBookPage;

    @Override
    public GuiTextField getSearchBar() {
        return searchBar;
    }

    @Override
    public RecipeBookPage getRecipeBookPage() {
        return recipeBookPage;
    }
}