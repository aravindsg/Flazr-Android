import com.flazr.*

def videoId = "201620741"

def url = "http://video.music.yahoo.com/ver/268.0/process/getPlaylistFOP.php?node_id=v" \
        + videoId + "&tech=flash&bitrate=58&mode=&lg=Lox6nE093DOEBHJ_XTuPaP&vidH=326&vidW=576&rd=new.music.yahoo.com&lang=us&tf=controls&eventid=1301797&tk=";

def xml = Utils.getOverHttp(url)
println xml

def data = new XmlSlurper().parseText(xml)