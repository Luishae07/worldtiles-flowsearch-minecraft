package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** /credits: where the data of the world comes from (what each part of the game uses). */
public final class Credits implements CommandExecutor {
    private static final String[][] LINES = {
            {"The buildings (outlines and heights)", "Microsoft Building Footprints (open data, found in satellite pictures)", "https://github.com/microsoft/GlobalMLBuildingFootprints"},
            {"How tall the buildings are where nobody measured them", "GHSL average building height 2018, European Commission Joint Research Centre (open data)", "https://human-settlement.emergency.copernicus.eu"},
            {"Towers, spires and roofs of buildings (building parts)", "Overture Maps Foundation, building parts (ODbL / CDLA, from OpenStreetMap and others)", "https://overturemaps.org"},
            {"Real heights of the buildings of France", "BD TOPO bâtiments, IGN (Licence Ouverte 2.0), through the Géoplateforme web service", "https://geoservices.ign.fr/bdtopo"},
            {"Real buildings of Berlin (heights and roofs)", "3D-Stadtmodell LoD2, Land Berlin (open data)", "https://gdi.berlin.de"},
            {"Real buildings of the Netherlands (heights and roofs)", "3DBAG, TU Delft 3D geoinformation (CC BY 4.0)", "https://3dbag.nl"},
            {"The colours of the roofs", "Esri World Imagery (satellite pictures, only read for the average colour of a roof)", "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9"},
            {"Landmark models (the Eiffel Tower, Notre-Dame, Big Ben ...)", "Open Landmarks (CC BY 4.0), placed with OpenStreetMap data (ODbL)", "https://github.com/benjamintd/open-landmarks"},
            {"More than 1,000 landmark models (Burj Khalifa, Colosseum, Sydney Opera House, skylines ...)", "Landmarks by Alex Wohlbruck (CC0, made with AI help), placed with OpenStreetMap data (ODbL)", "https://github.com/alexwohlbruck/landmarks"},
            {"Water, bare ground, built-up areas and where the trees are", "ESA WorldCover 10 m land cover (CC BY 4.0)", "https://esa-worldcover.org"},
            {"The roads and railways, shops, hotels, stops, traffic lights and indoor rooms", "OpenStreetMap contributors (ODbL), served by Postpass of Geofabrik; place search by Nominatim", "https://www.openstreetmap.org/copyright"},
            {"Heights of the ground (outside Berlin)", "Terrain tiles on AWS (Mapzen Terrain Tiles: SRTM, NASA and other public data)", "https://registry.opendata.aws/terrain-tiles/"},
            {"Heights of the ground in Berlin (every metre)", "ATKIS Digitales Geländemodell DGM1, Land Berlin (open data, no key)", "https://gdi.berlin.de/data/dgm1/atom"},
            {"Trains, S-Bahn, U-Bahn and trams of Berlin and Brandenburg", "VBB timetable data (GTFS)", "https://www.vbb.de/vbbgtfs"},
            {"Flights in the sky (/flights, /ride)", "adsb.fi open data (live aircraft positions)", "https://adsb.fi"},
            {"Football fixtures", "TheSportsDB (times and teams; the goals in the game are made up)", "https://www.thesportsdb.com"},
            {"Real weather", "Tomorrow.io and Open-Meteo", "https://open-meteo.com"},
            {"The Moon (/moon)", "NASA Lunar Orbiter Laser Altimeter (LOLA) heights and NASA Moon Trek images", "https://trek.nasa.gov/moon/"},
            {"The Earth in the sky of the Moon", "NASA Earth images", "https://visibleearth.nasa.gov"},
            {"The server and the world", "Paper (the Minecraft server software), WorldTiles by Flowsearch", "https://papermc.io"},
    };

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        s.sendMessage(Component.text("Credits - where the data of this world comes from:", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (String[] l : LINES) {
            s.sendMessage(Component.text("• " + l[0], NamedTextColor.WHITE)
                    .append(Component.newline()).append(Component.text("   " + l[1], NamedTextColor.GRAY))
                    .append(Component.newline()).append(Component.text("   " + l[2], NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(l[2]))));
        }
        s.sendMessage(Component.text("Thank you to everybody who maps, measures and shares data for free.", NamedTextColor.GREEN));
        return true;
    }
}
