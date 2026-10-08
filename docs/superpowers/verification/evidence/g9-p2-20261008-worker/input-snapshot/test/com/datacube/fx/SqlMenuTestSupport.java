package com.datacube.fx;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
final class SqlMenuTestSupport {
    static MenuItem find(Node root,String id) {
        for(Node node:root.lookupAll(".menu-button"))
            for(MenuItem item:((MenuButton)node).getItems()) if(id.equals(item.getId())) return item;
        throw new AssertionError("Missing menu action "+id);
    }
}
