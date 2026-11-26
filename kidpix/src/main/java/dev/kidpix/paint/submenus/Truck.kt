@file:JvmName("submenus_Truck")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/truck.js

var KiddoPaint.Submenu.truck: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Truck 1",
            imgSrc = "img/tool-submenu-truck-192.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 50.0
                KiddoPaint.Tools.Cut.width = 50.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-193.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 25.0
                KiddoPaint.Tools.Cut.width = 25.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-194.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 10.0
                KiddoPaint.Tools.Cut.width = 10.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-195.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 5.0
                KiddoPaint.Tools.Cut.width = 5.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-196.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 1.0
                KiddoPaint.Tools.Cut.width = 1.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-197.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 50.0
                KiddoPaint.Tools.Cut.width = 25.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-198.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 25.0
                KiddoPaint.Tools.Cut.width = 10.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-199.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 10.0
                KiddoPaint.Tools.Cut.width = 5.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-200.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 5.0
                KiddoPaint.Tools.Cut.width = 2.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-201.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 25.0
                KiddoPaint.Tools.Cut.width = 50.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-202.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 10.0
                KiddoPaint.Tools.Cut.width = 25.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-203.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 5.0
                KiddoPaint.Tools.Cut.width = 10.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-204.png",
            handler = {
                KiddoPaint.Tools.Cut.length = 2.0
                KiddoPaint.Tools.Cut.width = 5.0
            },
        ),
        SubmenuItem(
            name = "Truck 2",
            imgSrc = "img/tool-submenu-truck-205.png",
            handler = {
                KiddoPaint.Sounds.unimpl()
            },
        ),
    )
}
