package io.github.topiautio.blemeshctl.timer

/** Exact outputs of Python `blemeshctl.timers.fade_program_text`; generated, do not edit by hand. */
object FadeGoldenVectors {
    class Vector(val name: String, val color: String?, val white: Int?, val from: Int, val to: Int?, val overSeconds: Int, val description: String, val text: String)

    val all: List<Vector> = listOf(
        Vector("colourToOff1m", "ff8800", null, 100, null, 60, "fade ff8800 100->off over 1m",
            "# fade ff8800 100->off over 1m\n" +
            "color ff8800 100\n" +
            "wait 2s\n" +
            "color ff8800 97\n" +
            "wait 2s\n" +
            "color ff8800 93\n" +
            "wait 2s\n" +
            "color ff8800 90\n" +
            "wait 2s\n" +
            "color ff8800 86\n" +
            "wait 2s\n" +
            "color ff8800 83\n" +
            "wait 2s\n" +
            "color ff8800 80\n" +
            "wait 2s\n" +
            "color ff8800 76\n" +
            "wait 2s\n" +
            "color ff8800 73\n" +
            "wait 2s\n" +
            "color ff8800 69\n" +
            "wait 2s\n" +
            "color ff8800 66\n" +
            "wait 2s\n" +
            "color ff8800 62\n" +
            "wait 2s\n" +
            "color ff8800 59\n" +
            "wait 2s\n" +
            "color ff8800 56\n" +
            "wait 2s\n" +
            "color ff8800 52\n" +
            "wait 2s\n" +
            "color ff8800 49\n" +
            "wait 2s\n" +
            "color ff8800 45\n" +
            "wait 2s\n" +
            "color ff8800 42\n" +
            "wait 2s\n" +
            "color ff8800 39\n" +
            "wait 2s\n" +
            "color ff8800 35\n" +
            "wait 2s\n" +
            "color ff8800 32\n" +
            "wait 2s\n" +
            "color ff8800 28\n" +
            "wait 2s\n" +
            "color ff8800 25\n" +
            "wait 2s\n" +
            "color ff8800 21\n" +
            "wait 2s\n" +
            "color ff8800 18\n" +
            "wait 2s\n" +
            "color ff8800 15\n" +
            "wait 2s\n" +
            "color ff8800 11\n" +
            "wait 2s\n" +
            "color ff8800 8\n" +
            "wait 2s\n" +
            "color ff8800 4\n" +
            "wait 2s\n" +
            "color ff8800 1\n" +
            "wait 2s\n" +
            "off\n"),
        Vector("colourToOff20m", "ff8800", null, 100, null, 1200, "fade ff8800 100->off over 20m",
            "# fade ff8800 100->off over 20m\n" +
            "color ff8800 100\n" +
            "wait 12s\n" +
            "color ff8800 99\n" +
            "wait 12s\n" +
            "color ff8800 98\n" +
            "wait 12s\n" +
            "color ff8800 97\n" +
            "wait 12s\n" +
            "color ff8800 96\n" +
            "wait 12s\n" +
            "color ff8800 95\n" +
            "wait 12s\n" +
            "color ff8800 94\n" +
            "wait 12s\n" +
            "color ff8800 93\n" +
            "wait 12s\n" +
            "color ff8800 92\n" +
            "wait 12s\n" +
            "color ff8800 91\n" +
            "wait 12s\n" +
            "color ff8800 90\n" +
            "wait 12s\n" +
            "color ff8800 89\n" +
            "wait 12s\n" +
            "color ff8800 88\n" +
            "wait 12s\n" +
            "color ff8800 87\n" +
            "wait 12s\n" +
            "color ff8800 86\n" +
            "wait 12s\n" +
            "color ff8800 85\n" +
            "wait 12s\n" +
            "color ff8800 84\n" +
            "wait 12s\n" +
            "color ff8800 83\n" +
            "wait 12s\n" +
            "color ff8800 82\n" +
            "wait 12s\n" +
            "color ff8800 81\n" +
            "wait 12s\n" +
            "color ff8800 80\n" +
            "wait 12s\n" +
            "color ff8800 79\n" +
            "wait 12s\n" +
            "color ff8800 78\n" +
            "wait 12s\n" +
            "color ff8800 77\n" +
            "wait 12s\n" +
            "color ff8800 76\n" +
            "wait 12s\n" +
            "color ff8800 75\n" +
            "wait 12s\n" +
            "color ff8800 74\n" +
            "wait 12s\n" +
            "color ff8800 73\n" +
            "wait 12s\n" +
            "color ff8800 72\n" +
            "wait 12s\n" +
            "color ff8800 71\n" +
            "wait 12s\n" +
            "color ff8800 70\n" +
            "wait 12s\n" +
            "color ff8800 69\n" +
            "wait 12s\n" +
            "color ff8800 68\n" +
            "wait 12s\n" +
            "color ff8800 67\n" +
            "wait 12s\n" +
            "color ff8800 66\n" +
            "wait 12s\n" +
            "color ff8800 65\n" +
            "wait 12s\n" +
            "color ff8800 64\n" +
            "wait 12s\n" +
            "color ff8800 63\n" +
            "wait 12s\n" +
            "color ff8800 62\n" +
            "wait 12s\n" +
            "color ff8800 61\n" +
            "wait 12s\n" +
            "color ff8800 60\n" +
            "wait 12s\n" +
            "color ff8800 59\n" +
            "wait 12s\n" +
            "color ff8800 58\n" +
            "wait 12s\n" +
            "color ff8800 57\n" +
            "wait 12s\n" +
            "color ff8800 56\n" +
            "wait 12s\n" +
            "color ff8800 55\n" +
            "wait 12s\n" +
            "color ff8800 54\n" +
            "wait 12s\n" +
            "color ff8800 53\n" +
            "wait 12s\n" +
            "color ff8800 52\n" +
            "wait 12s\n" +
            "color ff8800 51\n" +
            "wait 12s\n" +
            "color ff8800 50\n" +
            "wait 12s\n" +
            "color ff8800 49\n" +
            "wait 12s\n" +
            "color ff8800 48\n" +
            "wait 12s\n" +
            "color ff8800 47\n" +
            "wait 12s\n" +
            "color ff8800 46\n" +
            "wait 12s\n" +
            "color ff8800 45\n" +
            "wait 12s\n" +
            "color ff8800 44\n" +
            "wait 12s\n" +
            "color ff8800 43\n" +
            "wait 12s\n" +
            "color ff8800 42\n" +
            "wait 12s\n" +
            "color ff8800 41\n" +
            "wait 12s\n" +
            "color ff8800 40\n" +
            "wait 12s\n" +
            "color ff8800 39\n" +
            "wait 12s\n" +
            "color ff8800 38\n" +
            "wait 12s\n" +
            "color ff8800 37\n" +
            "wait 12s\n" +
            "color ff8800 36\n" +
            "wait 12s\n" +
            "color ff8800 35\n" +
            "wait 12s\n" +
            "color ff8800 34\n" +
            "wait 12s\n" +
            "color ff8800 33\n" +
            "wait 12s\n" +
            "color ff8800 32\n" +
            "wait 12s\n" +
            "color ff8800 31\n" +
            "wait 12s\n" +
            "color ff8800 30\n" +
            "wait 12s\n" +
            "color ff8800 29\n" +
            "wait 12s\n" +
            "color ff8800 28\n" +
            "wait 12s\n" +
            "color ff8800 27\n" +
            "wait 12s\n" +
            "color ff8800 26\n" +
            "wait 12s\n" +
            "color ff8800 25\n" +
            "wait 12s\n" +
            "color ff8800 24\n" +
            "wait 12s\n" +
            "color ff8800 23\n" +
            "wait 12s\n" +
            "color ff8800 22\n" +
            "wait 12s\n" +
            "color ff8800 21\n" +
            "wait 12s\n" +
            "color ff8800 20\n" +
            "wait 12s\n" +
            "color ff8800 19\n" +
            "wait 12s\n" +
            "color ff8800 18\n" +
            "wait 12s\n" +
            "color ff8800 17\n" +
            "wait 12s\n" +
            "color ff8800 16\n" +
            "wait 12s\n" +
            "color ff8800 15\n" +
            "wait 12s\n" +
            "color ff8800 14\n" +
            "wait 12s\n" +
            "color ff8800 13\n" +
            "wait 12s\n" +
            "color ff8800 12\n" +
            "wait 12s\n" +
            "color ff8800 11\n" +
            "wait 12s\n" +
            "color ff8800 10\n" +
            "wait 12s\n" +
            "color ff8800 9\n" +
            "wait 12s\n" +
            "color ff8800 8\n" +
            "wait 12s\n" +
            "color ff8800 7\n" +
            "wait 12s\n" +
            "color ff8800 6\n" +
            "wait 12s\n" +
            "color ff8800 5\n" +
            "wait 12s\n" +
            "color ff8800 4\n" +
            "wait 12s\n" +
            "color ff8800 3\n" +
            "wait 12s\n" +
            "color ff8800 2\n" +
            "wait 12s\n" +
            "color ff8800 1\n" +
            "wait 12s\n" +
            "off\n"),
        Vector("colourToOff24h", "FF8800", null, 100, null, 86400, "fade ff8800 100->off over 24h",
            "# fade ff8800 100->off over 24h\n" +
            "color ff8800 100\n" +
            "wait 864s\n" +
            "color ff8800 99\n" +
            "wait 864s\n" +
            "color ff8800 98\n" +
            "wait 864s\n" +
            "color ff8800 97\n" +
            "wait 864s\n" +
            "color ff8800 96\n" +
            "wait 864s\n" +
            "color ff8800 95\n" +
            "wait 864s\n" +
            "color ff8800 94\n" +
            "wait 864s\n" +
            "color ff8800 93\n" +
            "wait 864s\n" +
            "color ff8800 92\n" +
            "wait 864s\n" +
            "color ff8800 91\n" +
            "wait 864s\n" +
            "color ff8800 90\n" +
            "wait 864s\n" +
            "color ff8800 89\n" +
            "wait 864s\n" +
            "color ff8800 88\n" +
            "wait 864s\n" +
            "color ff8800 87\n" +
            "wait 864s\n" +
            "color ff8800 86\n" +
            "wait 864s\n" +
            "color ff8800 85\n" +
            "wait 864s\n" +
            "color ff8800 84\n" +
            "wait 864s\n" +
            "color ff8800 83\n" +
            "wait 864s\n" +
            "color ff8800 82\n" +
            "wait 864s\n" +
            "color ff8800 81\n" +
            "wait 864s\n" +
            "color ff8800 80\n" +
            "wait 864s\n" +
            "color ff8800 79\n" +
            "wait 864s\n" +
            "color ff8800 78\n" +
            "wait 864s\n" +
            "color ff8800 77\n" +
            "wait 864s\n" +
            "color ff8800 76\n" +
            "wait 864s\n" +
            "color ff8800 75\n" +
            "wait 864s\n" +
            "color ff8800 74\n" +
            "wait 864s\n" +
            "color ff8800 73\n" +
            "wait 864s\n" +
            "color ff8800 72\n" +
            "wait 864s\n" +
            "color ff8800 71\n" +
            "wait 864s\n" +
            "color ff8800 70\n" +
            "wait 864s\n" +
            "color ff8800 69\n" +
            "wait 864s\n" +
            "color ff8800 68\n" +
            "wait 864s\n" +
            "color ff8800 67\n" +
            "wait 864s\n" +
            "color ff8800 66\n" +
            "wait 864s\n" +
            "color ff8800 65\n" +
            "wait 864s\n" +
            "color ff8800 64\n" +
            "wait 864s\n" +
            "color ff8800 63\n" +
            "wait 864s\n" +
            "color ff8800 62\n" +
            "wait 864s\n" +
            "color ff8800 61\n" +
            "wait 864s\n" +
            "color ff8800 60\n" +
            "wait 864s\n" +
            "color ff8800 59\n" +
            "wait 864s\n" +
            "color ff8800 58\n" +
            "wait 864s\n" +
            "color ff8800 57\n" +
            "wait 864s\n" +
            "color ff8800 56\n" +
            "wait 864s\n" +
            "color ff8800 55\n" +
            "wait 864s\n" +
            "color ff8800 54\n" +
            "wait 864s\n" +
            "color ff8800 53\n" +
            "wait 864s\n" +
            "color ff8800 52\n" +
            "wait 864s\n" +
            "color ff8800 51\n" +
            "wait 864s\n" +
            "color ff8800 50\n" +
            "wait 864s\n" +
            "color ff8800 49\n" +
            "wait 864s\n" +
            "color ff8800 48\n" +
            "wait 864s\n" +
            "color ff8800 47\n" +
            "wait 864s\n" +
            "color ff8800 46\n" +
            "wait 864s\n" +
            "color ff8800 45\n" +
            "wait 864s\n" +
            "color ff8800 44\n" +
            "wait 864s\n" +
            "color ff8800 43\n" +
            "wait 864s\n" +
            "color ff8800 42\n" +
            "wait 864s\n" +
            "color ff8800 41\n" +
            "wait 864s\n" +
            "color ff8800 40\n" +
            "wait 864s\n" +
            "color ff8800 39\n" +
            "wait 864s\n" +
            "color ff8800 38\n" +
            "wait 864s\n" +
            "color ff8800 37\n" +
            "wait 864s\n" +
            "color ff8800 36\n" +
            "wait 864s\n" +
            "color ff8800 35\n" +
            "wait 864s\n" +
            "color ff8800 34\n" +
            "wait 864s\n" +
            "color ff8800 33\n" +
            "wait 864s\n" +
            "color ff8800 32\n" +
            "wait 864s\n" +
            "color ff8800 31\n" +
            "wait 864s\n" +
            "color ff8800 30\n" +
            "wait 864s\n" +
            "color ff8800 29\n" +
            "wait 864s\n" +
            "color ff8800 28\n" +
            "wait 864s\n" +
            "color ff8800 27\n" +
            "wait 864s\n" +
            "color ff8800 26\n" +
            "wait 864s\n" +
            "color ff8800 25\n" +
            "wait 864s\n" +
            "color ff8800 24\n" +
            "wait 864s\n" +
            "color ff8800 23\n" +
            "wait 864s\n" +
            "color ff8800 22\n" +
            "wait 864s\n" +
            "color ff8800 21\n" +
            "wait 864s\n" +
            "color ff8800 20\n" +
            "wait 864s\n" +
            "color ff8800 19\n" +
            "wait 864s\n" +
            "color ff8800 18\n" +
            "wait 864s\n" +
            "color ff8800 17\n" +
            "wait 864s\n" +
            "color ff8800 16\n" +
            "wait 864s\n" +
            "color ff8800 15\n" +
            "wait 864s\n" +
            "color ff8800 14\n" +
            "wait 864s\n" +
            "color ff8800 13\n" +
            "wait 864s\n" +
            "color ff8800 12\n" +
            "wait 864s\n" +
            "color ff8800 11\n" +
            "wait 864s\n" +
            "color ff8800 10\n" +
            "wait 864s\n" +
            "color ff8800 9\n" +
            "wait 864s\n" +
            "color ff8800 8\n" +
            "wait 864s\n" +
            "color ff8800 7\n" +
            "wait 864s\n" +
            "color ff8800 6\n" +
            "wait 864s\n" +
            "color ff8800 5\n" +
            "wait 864s\n" +
            "color ff8800 4\n" +
            "wait 864s\n" +
            "color ff8800 3\n" +
            "wait 864s\n" +
            "color ff8800 2\n" +
            "wait 864s\n" +
            "color ff8800 1\n" +
            "wait 864s\n" +
            "off\n"),
        Vector("whiteToOff100s", null, 2700, 50, null, 100, "fade white 2700K 50->off over 1m40s",
            "# fade white 2700K 50->off over 1m40s\n" +
            "white 2700K 50\n" +
            "wait 2s\n" +
            "white 2700K 49\n" +
            "wait 2s\n" +
            "white 2700K 48\n" +
            "wait 2s\n" +
            "white 2700K 47\n" +
            "wait 2s\n" +
            "white 2700K 46\n" +
            "wait 2s\n" +
            "white 2700K 45\n" +
            "wait 2s\n" +
            "white 2700K 44\n" +
            "wait 2s\n" +
            "white 2700K 43\n" +
            "wait 2s\n" +
            "white 2700K 42\n" +
            "wait 2s\n" +
            "white 2700K 41\n" +
            "wait 2s\n" +
            "white 2700K 40\n" +
            "wait 2s\n" +
            "white 2700K 39\n" +
            "wait 2s\n" +
            "white 2700K 38\n" +
            "wait 2s\n" +
            "white 2700K 37\n" +
            "wait 2s\n" +
            "white 2700K 36\n" +
            "wait 2s\n" +
            "white 2700K 35\n" +
            "wait 2s\n" +
            "white 2700K 34\n" +
            "wait 2s\n" +
            "white 2700K 33\n" +
            "wait 2s\n" +
            "white 2700K 32\n" +
            "wait 2s\n" +
            "white 2700K 31\n" +
            "wait 2s\n" +
            "white 2700K 30\n" +
            "wait 2s\n" +
            "white 2700K 29\n" +
            "wait 2s\n" +
            "white 2700K 28\n" +
            "wait 2s\n" +
            "white 2700K 27\n" +
            "wait 2s\n" +
            "white 2700K 26\n" +
            "wait 2s\n" +
            "white 2700K 25\n" +
            "wait 2s\n" +
            "white 2700K 24\n" +
            "wait 2s\n" +
            "white 2700K 23\n" +
            "wait 2s\n" +
            "white 2700K 22\n" +
            "wait 2s\n" +
            "white 2700K 21\n" +
            "wait 2s\n" +
            "white 2700K 20\n" +
            "wait 2s\n" +
            "white 2700K 19\n" +
            "wait 2s\n" +
            "white 2700K 18\n" +
            "wait 2s\n" +
            "white 2700K 17\n" +
            "wait 2s\n" +
            "white 2700K 16\n" +
            "wait 2s\n" +
            "white 2700K 15\n" +
            "wait 2s\n" +
            "white 2700K 14\n" +
            "wait 2s\n" +
            "white 2700K 13\n" +
            "wait 2s\n" +
            "white 2700K 12\n" +
            "wait 2s\n" +
            "white 2700K 11\n" +
            "wait 2s\n" +
            "white 2700K 10\n" +
            "wait 2s\n" +
            "white 2700K 9\n" +
            "wait 2s\n" +
            "white 2700K 8\n" +
            "wait 2s\n" +
            "white 2700K 7\n" +
            "wait 2s\n" +
            "white 2700K 6\n" +
            "wait 2s\n" +
            "white 2700K 5\n" +
            "wait 2s\n" +
            "white 2700K 4\n" +
            "wait 2s\n" +
            "white 2700K 3\n" +
            "wait 2s\n" +
            "white 2700K 2\n" +
            "wait 2s\n" +
            "white 2700K 1\n" +
            "wait 2s\n" +
            "off\n"),
        Vector("colourDown", "#00ff00", null, 80, 20, 60, "fade 00ff00 80->20 over 1m",
            "# fade 00ff00 80->20 over 1m\n" +
            "color 00ff00 80\n" +
            "wait 2.069s\n" +
            "color 00ff00 78\n" +
            "wait 2.069s\n" +
            "color 00ff00 76\n" +
            "wait 2.069s\n" +
            "color 00ff00 74\n" +
            "wait 2.069s\n" +
            "color 00ff00 72\n" +
            "wait 2.069s\n" +
            "color 00ff00 70\n" +
            "wait 2.069s\n" +
            "color 00ff00 68\n" +
            "wait 2.069s\n" +
            "color 00ff00 66\n" +
            "wait 2.069s\n" +
            "color 00ff00 63\n" +
            "wait 2.069s\n" +
            "color 00ff00 61\n" +
            "wait 2.069s\n" +
            "color 00ff00 59\n" +
            "wait 2.069s\n" +
            "color 00ff00 57\n" +
            "wait 2.069s\n" +
            "color 00ff00 55\n" +
            "wait 2.069s\n" +
            "color 00ff00 53\n" +
            "wait 2.069s\n" +
            "color 00ff00 51\n" +
            "wait 2.069s\n" +
            "color 00ff00 49\n" +
            "wait 2.069s\n" +
            "color 00ff00 47\n" +
            "wait 2.069s\n" +
            "color 00ff00 45\n" +
            "wait 2.069s\n" +
            "color 00ff00 43\n" +
            "wait 2.069s\n" +
            "color 00ff00 41\n" +
            "wait 2.069s\n" +
            "color 00ff00 39\n" +
            "wait 2.069s\n" +
            "color 00ff00 37\n" +
            "wait 2.069s\n" +
            "color 00ff00 34\n" +
            "wait 2.069s\n" +
            "color 00ff00 32\n" +
            "wait 2.069s\n" +
            "color 00ff00 30\n" +
            "wait 2.069s\n" +
            "color 00ff00 28\n" +
            "wait 2.069s\n" +
            "color 00ff00 26\n" +
            "wait 2.069s\n" +
            "color 00ff00 24\n" +
            "wait 2.069s\n" +
            "color 00ff00 22\n" +
            "wait 2.069s\n" +
            "color 00ff00 20\n"),
        Vector("colourUp", "00ff00", null, 10, 90, 60, "fade 00ff00 10->90 over 1m",
            "# fade 00ff00 10->90 over 1m\n" +
            "color 00ff00 10\n" +
            "wait 2.069s\n" +
            "color 00ff00 13\n" +
            "wait 2.069s\n" +
            "color 00ff00 16\n" +
            "wait 2.069s\n" +
            "color 00ff00 18\n" +
            "wait 2.069s\n" +
            "color 00ff00 21\n" +
            "wait 2.069s\n" +
            "color 00ff00 24\n" +
            "wait 2.069s\n" +
            "color 00ff00 27\n" +
            "wait 2.069s\n" +
            "color 00ff00 29\n" +
            "wait 2.069s\n" +
            "color 00ff00 32\n" +
            "wait 2.069s\n" +
            "color 00ff00 35\n" +
            "wait 2.069s\n" +
            "color 00ff00 38\n" +
            "wait 2.069s\n" +
            "color 00ff00 40\n" +
            "wait 2.069s\n" +
            "color 00ff00 43\n" +
            "wait 2.069s\n" +
            "color 00ff00 46\n" +
            "wait 2.069s\n" +
            "color 00ff00 49\n" +
            "wait 2.069s\n" +
            "color 00ff00 51\n" +
            "wait 2.069s\n" +
            "color 00ff00 54\n" +
            "wait 2.069s\n" +
            "color 00ff00 57\n" +
            "wait 2.069s\n" +
            "color 00ff00 60\n" +
            "wait 2.069s\n" +
            "color 00ff00 62\n" +
            "wait 2.069s\n" +
            "color 00ff00 65\n" +
            "wait 2.069s\n" +
            "color 00ff00 68\n" +
            "wait 2.069s\n" +
            "color 00ff00 71\n" +
            "wait 2.069s\n" +
            "color 00ff00 73\n" +
            "wait 2.069s\n" +
            "color 00ff00 76\n" +
            "wait 2.069s\n" +
            "color 00ff00 79\n" +
            "wait 2.069s\n" +
            "color 00ff00 82\n" +
            "wait 2.069s\n" +
            "color 00ff00 84\n" +
            "wait 2.069s\n" +
            "color 00ff00 87\n" +
            "wait 2.069s\n" +
            "color 00ff00 90\n"),
        Vector("whiteUp", null, 4000, 10, 90, 300, "fade white 4000K 10->90 over 5m",
            "# fade white 4000K 10->90 over 5m\n" +
            "white 4000K 10\n" +
            "wait 3.75s\n" +
            "white 4000K 11\n" +
            "wait 3.75s\n" +
            "white 4000K 12\n" +
            "wait 3.75s\n" +
            "white 4000K 13\n" +
            "wait 3.75s\n" +
            "white 4000K 14\n" +
            "wait 3.75s\n" +
            "white 4000K 15\n" +
            "wait 3.75s\n" +
            "white 4000K 16\n" +
            "wait 3.75s\n" +
            "white 4000K 17\n" +
            "wait 3.75s\n" +
            "white 4000K 18\n" +
            "wait 3.75s\n" +
            "white 4000K 19\n" +
            "wait 3.75s\n" +
            "white 4000K 20\n" +
            "wait 3.75s\n" +
            "white 4000K 21\n" +
            "wait 3.75s\n" +
            "white 4000K 22\n" +
            "wait 3.75s\n" +
            "white 4000K 23\n" +
            "wait 3.75s\n" +
            "white 4000K 24\n" +
            "wait 3.75s\n" +
            "white 4000K 25\n" +
            "wait 3.75s\n" +
            "white 4000K 26\n" +
            "wait 3.75s\n" +
            "white 4000K 27\n" +
            "wait 3.75s\n" +
            "white 4000K 28\n" +
            "wait 3.75s\n" +
            "white 4000K 29\n" +
            "wait 3.75s\n" +
            "white 4000K 30\n" +
            "wait 3.75s\n" +
            "white 4000K 31\n" +
            "wait 3.75s\n" +
            "white 4000K 32\n" +
            "wait 3.75s\n" +
            "white 4000K 33\n" +
            "wait 3.75s\n" +
            "white 4000K 34\n" +
            "wait 3.75s\n" +
            "white 4000K 35\n" +
            "wait 3.75s\n" +
            "white 4000K 36\n" +
            "wait 3.75s\n" +
            "white 4000K 37\n" +
            "wait 3.75s\n" +
            "white 4000K 38\n" +
            "wait 3.75s\n" +
            "white 4000K 39\n" +
            "wait 3.75s\n" +
            "white 4000K 40\n" +
            "wait 3.75s\n" +
            "white 4000K 41\n" +
            "wait 3.75s\n" +
            "white 4000K 42\n" +
            "wait 3.75s\n" +
            "white 4000K 43\n" +
            "wait 3.75s\n" +
            "white 4000K 44\n" +
            "wait 3.75s\n" +
            "white 4000K 45\n" +
            "wait 3.75s\n" +
            "white 4000K 46\n" +
            "wait 3.75s\n" +
            "white 4000K 47\n" +
            "wait 3.75s\n" +
            "white 4000K 48\n" +
            "wait 3.75s\n" +
            "white 4000K 49\n" +
            "wait 3.75s\n" +
            "white 4000K 50\n" +
            "wait 3.75s\n" +
            "white 4000K 51\n" +
            "wait 3.75s\n" +
            "white 4000K 52\n" +
            "wait 3.75s\n" +
            "white 4000K 53\n" +
            "wait 3.75s\n" +
            "white 4000K 54\n" +
            "wait 3.75s\n" +
            "white 4000K 55\n" +
            "wait 3.75s\n" +
            "white 4000K 56\n" +
            "wait 3.75s\n" +
            "white 4000K 57\n" +
            "wait 3.75s\n" +
            "white 4000K 58\n" +
            "wait 3.75s\n" +
            "white 4000K 59\n" +
            "wait 3.75s\n" +
            "white 4000K 60\n" +
            "wait 3.75s\n" +
            "white 4000K 61\n" +
            "wait 3.75s\n" +
            "white 4000K 62\n" +
            "wait 3.75s\n" +
            "white 4000K 63\n" +
            "wait 3.75s\n" +
            "white 4000K 64\n" +
            "wait 3.75s\n" +
            "white 4000K 65\n" +
            "wait 3.75s\n" +
            "white 4000K 66\n" +
            "wait 3.75s\n" +
            "white 4000K 67\n" +
            "wait 3.75s\n" +
            "white 4000K 68\n" +
            "wait 3.75s\n" +
            "white 4000K 69\n" +
            "wait 3.75s\n" +
            "white 4000K 70\n" +
            "wait 3.75s\n" +
            "white 4000K 71\n" +
            "wait 3.75s\n" +
            "white 4000K 72\n" +
            "wait 3.75s\n" +
            "white 4000K 73\n" +
            "wait 3.75s\n" +
            "white 4000K 74\n" +
            "wait 3.75s\n" +
            "white 4000K 75\n" +
            "wait 3.75s\n" +
            "white 4000K 76\n" +
            "wait 3.75s\n" +
            "white 4000K 77\n" +
            "wait 3.75s\n" +
            "white 4000K 78\n" +
            "wait 3.75s\n" +
            "white 4000K 79\n" +
            "wait 3.75s\n" +
            "white 4000K 80\n" +
            "wait 3.75s\n" +
            "white 4000K 81\n" +
            "wait 3.75s\n" +
            "white 4000K 82\n" +
            "wait 3.75s\n" +
            "white 4000K 83\n" +
            "wait 3.75s\n" +
            "white 4000K 84\n" +
            "wait 3.75s\n" +
            "white 4000K 85\n" +
            "wait 3.75s\n" +
            "white 4000K 86\n" +
            "wait 3.75s\n" +
            "white 4000K 87\n" +
            "wait 3.75s\n" +
            "white 4000K 88\n" +
            "wait 3.75s\n" +
            "white 4000K 89\n" +
            "wait 3.75s\n" +
            "white 4000K 90\n"),
        Vector("veryShortOneSecond", "ff0000", null, 100, null, 1, "fade ff0000 100->off over 1s",
            "# fade ff0000 100->off over 1s\n" +
            "color ff0000 1\n" +
            "wait 1s\n" +
            "off\n"),
        Vector("veryShortThreeSeconds", "ff0000", null, 100, null, 3, "fade ff0000 100->off over 3s",
            "# fade ff0000 100->off over 3s\n" +
            "color ff0000 1\n" +
            "wait 3s\n" +
            "off\n"),
        Vector("veryShortFiveSeconds", "ff0000", null, 100, null, 5, "fade ff0000 100->off over 5s",
            "# fade ff0000 100->off over 5s\n" +
            "color ff0000 100\n" +
            "wait 2.5s\n" +
            "color ff0000 1\n" +
            "wait 2.5s\n" +
            "off\n"),
        Vector("twentySeconds", "ff8800", null, 100, null, 20, "fade ff8800 100->off over 20s",
            "# fade ff8800 100->off over 20s\n" +
            "color ff8800 100\n" +
            "wait 2s\n" +
            "color ff8800 89\n" +
            "wait 2s\n" +
            "color ff8800 78\n" +
            "wait 2s\n" +
            "color ff8800 67\n" +
            "wait 2s\n" +
            "color ff8800 56\n" +
            "wait 2s\n" +
            "color ff8800 45\n" +
            "wait 2s\n" +
            "color ff8800 34\n" +
            "wait 2s\n" +
            "color ff8800 23\n" +
            "wait 2s\n" +
            "color ff8800 12\n" +
            "wait 2s\n" +
            "color ff8800 1\n" +
            "wait 2s\n" +
            "off\n"),
        Vector("sameLevel", "ff0000", null, 40, 40, 60, "fade ff0000 40->40 over 1m",
            "# fade ff0000 40->40 over 1m\n" +
            "color ff0000 40\n"),
        Vector("fromOneToOff", "0000ff", null, 1, null, 30, "fade 0000ff 1->off over 30s",
            "# fade 0000ff 1->off over 30s\n" +
            "color 0000ff 1\n" +
            "wait 30s\n" +
            "off\n"),
        Vector("fractionalWaits", "0000ff", null, 20, 14, 100, "fade 0000ff 20->14 over 1m40s",
            "# fade 0000ff 20->14 over 1m40s\n" +
            "color 0000ff 20\n" +
            "wait 16.667s\n" +
            "color 0000ff 19\n" +
            "wait 16.667s\n" +
            "color 0000ff 18\n" +
            "wait 16.667s\n" +
            "color 0000ff 17\n" +
            "wait 16.667s\n" +
            "color 0000ff 16\n" +
            "wait 16.667s\n" +
            "color 0000ff 15\n" +
            "wait 16.667s\n" +
            "color 0000ff 14\n"),
        Vector("thirdsToOff", "0000ff", null, 10, null, 7, "fade 0000ff 10->off over 7s",
            "# fade 0000ff 10->off over 7s\n" +
            "color 0000ff 10\n" +
            "wait 2.333s\n" +
            "color 0000ff 6\n" +
            "wait 2.333s\n" +
            "color 0000ff 1\n" +
            "wait 2.333s\n" +
            "off\n"),
        Vector("oneStepToTarget", "0000ff", null, 60, 30, 3, "fade 0000ff 60->30 over 3s",
            "# fade 0000ff 60->30 over 3s\n" +
            "color 0000ff 30\n"),
    )
}
