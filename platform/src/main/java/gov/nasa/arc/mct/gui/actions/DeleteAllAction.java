/*******************************************************************************
 * Mission Control Technologies, Copyright (c) 2009-2012, United States Government
 * as represented by the Administrator of the National Aeronautics and Space 
 * Administration. All rights reserved.
 *
 * The MCT platform is licensed under the Apache License, Version 2.0 (the 
 * "License"); you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT 
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the 
 * License for the specific language governing permissions and limitations under 
 * the License.
 *
 * MCT includes source code licensed under additional open source licenses. See 
 * the MCT Open Source Licenses file included with this distribution or the About 
 * MCT Licenses dialog available at runtime from the MCT Help menu for additional 
 * information. 
 *******************************************************************************/
package gov.nasa.arc.mct.gui.actions;

import gov.nasa.arc.mct.components.AbstractComponent;
import gov.nasa.arc.mct.gui.ActionContext;
import gov.nasa.arc.mct.gui.ContextAwareAction;
import gov.nasa.arc.mct.gui.MCTMutableTreeNode;
import gov.nasa.arc.mct.gui.OptionBox;
import gov.nasa.arc.mct.gui.View;
import gov.nasa.arc.mct.gui.housing.MCTDirectoryArea;
import gov.nasa.arc.mct.gui.housing.MCTHousing;
import gov.nasa.arc.mct.gui.impl.ActionContextImpl;
import gov.nasa.arc.mct.platform.spi.PlatformAccess;
import gov.nasa.arc.mct.policy.PolicyContext;
import gov.nasa.arc.mct.policy.PolicyInfo;
import gov.nasa.arc.mct.policymgr.PolicyManagerImpl;
import gov.nasa.arc.mct.services.component.ViewInfo;
import gov.nasa.arc.mct.services.component.ViewType;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.tree.TreePath;

public class DeleteAllAction extends ContextAwareAction {
    private static final ResourceBundle bundle = ResourceBundle.getBundle("gov/nasa/arc/mct/gui/actions/Bundle"); 
    private static final long serialVersionUID = 3047419887471823851L;
    private static String WARNING = bundle.getString("DeleteAllWarningTitle");
    private static String TEXT = "Delete All";
    
    private TreePath[] selectedTreePaths;
    private ActionContextImpl actionContext;
    
    public DeleteAllAction() {
        super(TEXT);
    }
    
    @Override
    public boolean canHandle(ActionContext context) {
        actionContext = (ActionContextImpl) context;
        MCTHousing activeHousing = actionContext.getTargetHousing();
        if (activeHousing == null)
            return false;


        Collection<View> selection = 
            activeHousing.getSelectionProvider().getSelectedManifestations();
        
        if (selection.isEmpty()) {
            return false;
        }
        
        ViewInfo vi = selection.iterator().next().getInfo();
        
        if (!(vi != null && vi.getViewType() == ViewType.NODE)){
            return false;
        }

        if (!(activeHousing.getDirectoryArea() instanceof MCTDirectoryArea)) {
            return false;
        }
        
        MCTDirectoryArea directory = MCTDirectoryArea.class.cast(activeHousing.getDirectoryArea());
        MCTMutableTreeNode firstSelectedNode = directory.getSelectedDirectoryNode();
        
        if (firstSelectedNode == null)
            return false;
        
        JTree tree = firstSelectedNode.getParentTree();
        selectedTreePaths = tree.getSelectionPaths();
        return selectedTreePaths != null && selectedTreePaths.length > 0;
    }
    
    @Override
    public boolean isEnabled() {
        for (TreePath path : selectedTreePaths) {
            if (!isRemovable(path))
                return false;
        }
        return true;
    }
    
    @Override
    public void actionPerformed(ActionEvent e) {
        Map<String, AbstractComponent> toDelete = new HashMap<String, AbstractComponent>();
        Map<String, AbstractComponent> toRemove = new HashMap<String, AbstractComponent>();
        for (TreePath path : selectedTreePaths) {
            MCTMutableTreeNode selectedNode = (MCTMutableTreeNode) path.getLastPathComponent();            
            AbstractComponent selectedComponent = ((View) selectedNode.getUserObject()).getManifestedComponent();
            categorizeDescendants(selectedComponent, toDelete, toRemove);
        }
        Set<AbstractComponent> cannotRemove = findNonRemovableComponents(toDelete, toRemove);
        handleWarnings(toDelete.values(), toRemove.values(), cannotRemove);
    }
    
    private void categorizeDescendants(AbstractComponent component, 
            Map<String, AbstractComponent> toDelete, Map<String, AbstractComponent> toRemove) {
        // Assemble sets by component id
        String componentId = component.getComponentId();
        
        // Don't travel down cycles
        if (toDelete.containsKey(componentId) || toRemove.containsKey(componentId)) {
            return;
        }
        
        // Place into maps according to whether or not the component can be deleted
        if (component.canBeDeleted()) {
            toDelete.put(componentId, component);
        } else {
            toRemove.put(componentId, component);
        }
        
        // Finally, categorize descendants
        for (AbstractComponent child : component.getComponents()) {
            categorizeDescendants(child, toDelete, toRemove);
        }        
    }
    
    private Set<AbstractComponent> findNonRemovableComponents(
            Map<String, AbstractComponent> toDelete, Map<String, AbstractComponent> toRemove) {
        // Assemble a list of components which cannot be deleted
        Set<AbstractComponent> nonRemovable = new HashSet<AbstractComponent>();
        for (AbstractComponent remove : toRemove.values()) {
            // See if any referencing components are NOT in the list
            boolean safeToRemove = false;
            for (AbstractComponent ref : remove.getReferencingComponents()) {
                String refId = ref.getComponentId();
                if (!toDelete.containsKey(refId)) {
                    safeToRemove = true;
                    break;
                }
            }
            if (!safeToRemove) {
                nonRemovable.add(remove);
            }
        }
        return nonRemovable;
    }
    
    private void handleWarnings(Collection<AbstractComponent> toDelete, Collection<AbstractComponent> toRemove, 
            Collection<AbstractComponent> cannotRemove) {
        // Can only complete action if there are no components which cannot be removed
        if (cannotRemove.isEmpty()) {
            Object[] options = { bundle.getString("DeleteAllCoreText"), bundle.getString("DeleteAllAbortText") };
            int choice = OptionBox.showOptionDialog(actionContext.getWindowManifestation(), 
                    buildWarningPanel(toDelete),
                    WARNING,
                    OptionBox.YES_NO_OPTION,
                    OptionBox.WARNING_MESSAGE,
                    null,
                    options,
                    null);
            if (choice == 0) {
                for (AbstractComponent delete : toDelete) {
                    PlatformAccess.getPlatform().getWindowManager().closeWindows(delete.getComponentId());
                }
                PlatformAccess.getPlatform().getPersistenceProvider().delete(toDelete);
            }            
        } else {           
            OptionBox.showMessageDialog(actionContext.getWindowManifestation(), 
                    bundle.getString("DeleteAllErrorHasDescendantsText"), 
                    "ERROR: "+ WARNING, 
                    OptionBox.ERROR_MESSAGE);
        }
    }
    
    private JPanel buildWarningPanel(Collection<AbstractComponent> componentsToBeDeleted) {
        List<String> deleteComps = new ArrayList<String>(componentsToBeDeleted.size());
        for (AbstractComponent comp : componentsToBeDeleted) {
            deleteComps.add(comp.getDisplayName());
        }
        JPanel warning = new JPanel(new FlowLayout());
        warning.setPreferredSize(new Dimension(400,400));
        @SuppressWarnings({ "rawtypes", "unchecked" })
        JList deleteList = new JList(deleteComps.toArray());
        JScrollPane scrollPane2 = new JScrollPane(deleteList);
        scrollPane2.setPreferredSize(new Dimension(300,200));
        JLabel deletingObjectsLabel = new JLabel(bundle.getString("RemoveManifestationBecomesDelete"));
        deletingObjectsLabel.setPreferredSize(new Dimension(300,20));
        deletingObjectsLabel.setVerticalAlignment(SwingConstants.BOTTOM);
        deletingObjectsLabel.setHorizontalAlignment(SwingConstants.LEFT);
        JTextArea warningMessage = new JTextArea(bundle.getString("DeleteAllWarningText"));
        warningMessage.setWrapStyleWord(true);
        warningMessage.setLineWrap(true);
        warningMessage.setOpaque(false);
        warningMessage.setPreferredSize(new Dimension(300,200));
        warningMessage.setEditable(false);
        warning.add(warningMessage);
        warning.add(deletingObjectsLabel);
        warning.add(scrollPane2);
        return warning;
    }
    
    private boolean isRemovable(TreePath path) {
        MCTMutableTreeNode lastPathComponent = (MCTMutableTreeNode) path.getLastPathComponent();
        AbstractComponent selectedComponent = View.class.cast(lastPathComponent.getUserObject()).getManifestedComponent();

        MCTMutableTreeNode parentNode = (MCTMutableTreeNode) lastPathComponent.getParent();
        if (parentNode == null)
            return false;

        AbstractComponent parentComponent = ((View) parentNode.getUserObject()).getManifestedComponent();        
        if (!selectedComponent.canBeDeleted()) {
            return false;
        }
        
        
        PolicyContext context = new PolicyContext();
        context.setProperty(PolicyContext.PropertyName.TARGET_COMPONENT.getName(), parentComponent);
        context.setProperty(PolicyContext.PropertyName.ACTION.getName(), 'w');
        String compositionKey = PolicyInfo.CategoryType.COMPOSITION_POLICY_CATEGORY.getKey();
        return PolicyManagerImpl.getInstance().execute(compositionKey, context).getStatus();
    }
    
}
