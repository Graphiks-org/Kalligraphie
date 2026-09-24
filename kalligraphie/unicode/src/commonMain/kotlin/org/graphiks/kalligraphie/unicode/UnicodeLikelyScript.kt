package org.graphiks.kalligraphie.unicode

/**
 * Unicode 16.0 `likelySubtags` data.
 *
 * Used by the portable Unicode analyzer's script resolution.
 *
 * The table is derived from
 * `likelySubtags.xml`, SHA-256
 * `a8c085186c074062c9665cab5270313e77e118184dfd80465864e24de674bf42`.
 * It is the source reduced to the languages that carry no region, mapped to the script
 * their maximised tag names: that is the lookup the analyzer makes for an explicit
 * analysis language, and no other entry of the source can answer it.
 * The tags are stored 3 characters each in [LANGUAGE_TAGS], the shorter ones
 * padded with a space, and the script of each is the [SCRIPT_CODES] index spelled as
 * two hexadecimal digits in [LANGUAGE_SCRIPTS].
 */
internal object UnicodeLikelyScript {
    /** Pinned Unicode Character Database version that supplied this table. */
    internal const val unicodeVersion: String = "16.0"

    /**
     * Returns the likely ISO 15924 short code of [language], or null when the source has none.
     *
     * [language] is a bare language subtag, not a whole BCP 47 tag: the source keys on the language
     * alone, so a tag such as `en-US` has to be reduced before it is asked here.
     */
    internal fun scriptOf(language: String): String? {
        var low = 0
        var high = TAG_COUNT - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = compareTagAt(middle, language)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return SCRIPT_CODES[hexPair(LANGUAGE_SCRIPTS, middle)]
            }
        }
        return null
    }

    /** Compares the tag of [index] with [language], reading both one character at a time. */
    private fun compareTagAt(index: Int, language: String): Int {
        val base = index * TAG_WIDTH
        var offset = 0
        while (offset < TAG_WIDTH) {
            val stored = LANGUAGE_TAGS[base + offset]
            val given = if (offset < language.length) language[offset] else ' '
            if (stored != given) return stored.compareTo(given)
            offset += 1
        }
        return 0
    }

    private fun hexPair(hex: String, index: Int): Int =
        (hexDigit(hex[index * 2]) shl 4) or hexDigit(hex[index * 2 + 1])

    private fun hexDigit(character: Char): Int {
        val digit = character - '0'
        return if (digit < 10) digit else character - 'a' + 10
    }

    private const val TAG_WIDTH: Int = 3
    private const val TAG_COUNT: Int = 7196

    /** The source's language subtags, [TAG_WIDTH] characters each. */
    private val LANGUAGE_TAGS: String =
        "aa aaaaabaacaadaaeaafaagaahaaiaakaalaanaaoaapaaqaasaataauaawaaxaazab abaabbabcabdabeabfabgabhabi" +
        "ablabmabnaboabpabrabsabtabuabvabwabxabyabzacaacbacdaceacfachacmacnacpacqacracsactacuacvacwacxacy" +
        "aczadaadbaddadeadfadgadhadiadjadladnadoadqadradtaduadwadxadyadzae aeaaebaecaeeaekaelaemaeqaeraeu" +
        "aewaeyaezaf afbafdafeafhafiafkafnafoafpafsafuafzagaagbagcagdageagfaggaghagiagjagkaglagmagnagoagq" +
        "agragsagtaguagvagwagxagyagzahaahbahgahhahiahkahlahmahnahoahpahrahsahtaiaaibaicaidaieaifaigaiiaij" +
        "aikailaimainaioaipaiqairaitaiwaixaiyajaajgajiajnajwajzak akbakcakdakeakfakgakhakiakkaklakoakpakq" +
        "akraksaktakuakvakwakzalaalcaldalealfalhalialjalkallalmalnaloalpalqalraltalualwalxalyalzam amaamb" +
        "amcameamfamgamiamjamkammamnamoampamqamramsamtamuamvamwamxamyamzan anaanbancandaneanfanganhanianj" +
        "ankanlanmannanoanpanqanransantanuanvanwanxanyanzaoaaobaocaodaoeaofaogaoiaojaokaolaomaonaoraosaot" +
        "aoxaozapbapcapdapeapfapgaphapiapjapkaplapmapnapoappaprapsaptapuapvapwapxapyapzaqcaqdaqgaqkaqmaqn" +
        "aqraqtaqzar arcardarearhariarjarkarlarnaroarparqarrarsaruarwarxaryarzas asaasbascaseasgashasiasj" +
        "askaslasnasoasrassastasuasvasxasyaszataatbatcatdateatgatiatjatkatlatmatnatoatpatqatratsattatuatv" +
        "atwatxatyatzauaaucaudaugauhauiaujaukaulaumaunauoaupauqaurautauuauwauyauzav avbavdaviavkavlavmavn" +
        "avoavsavtavuavvawaawbawcaweawgawhawiawkawmawnawoawrawsawtawuawvawwawxawyaxbaxeaxgaxkaxlaxmaxxay " +
        "ayaaybaycaydayeaygayhayiaykaylaynayoaypayqaysaytayuayzaz azbazdazgazmaznazoaztazzba baababbacbae" +
        "bafbagbahbajbalbanbaobapbarbasbaubavbawbaxbaybbabbbbbcbbdbbebbfbbgbbibbjbbkbblbbmbbnbbobbpbbqbbr" +
        "bbsbbtbbubbvbbwbbxbbybcabcbbcdbcebcfbcgbchbcibcjbckbcmbcnbcobcpbcqbcrbcsbctbcubcvbcwbcybczbdabdb" +
        "bdcbddbdebdfbdgbdhbdibdjbdkbdlbdmbdnbdobdpbdqbdrbdsbdtbdubdvbdwbdxbdybdzbe beabebbecbedbeebefbeh" +
        "beibejbekbembeobepbeqbesbetbeubevbewbexbeybezbfabfbbfcbfdbfebffbfgbfhbfjbflbfmbfnbfobfpbfqbfsbft" +
        "bfubfwbfxbfybfzbg bgabgbbgcbgdbgfbggbgibgjbgnbgobgpbgqbgrbgsbgtbgubgvbgwbgxbgybgzbhabhbbhcbhdbhe" +
        "bhfbhgbhhbhibhjbhlbhmbhnbhobhpbhqbhrbhsbhtbhubhvbhwbhybhzbi biabibbidbiebifbigbikbilbimbinbiobip" +
        "biqbirbitbiubivbiwbiybizbjabjbbjcbjfbjgbjhbjibjjbjkbjlbjmbjnbjobjpbjrbjsbjtbjubjvbjwbjxbjybjzbka" +
        "bkcbkdbkfbkgbkhbkibkjbkkbklbkmbknbkobkpbkqbkrbksbktbkubkvbkwbkxbkybkzblablbblcbldbleblfblhbliblj" +
        "blkblmblnbloblpblqblrblsbltblvblwblxblyblzbm bmabmbbmcbmdbmebmfbmgbmhbmibmjbmkbmlbmmbmnbmobmpbmq" +
        "bmrbmsbmubmvbmwbmxbmzbn bnabnbbncbndbnebnfbngbnibnjbnkbnmbnnbnobnpbnqbnrbnsbnubnvbnwbnxbnybnzbo " +
        "boabobboebofbohbojbokbolbombonboobopboqborbotboubovbowboxboybozbpabpcbpdbpebpgbphbpibpjbpkbplbpm" +
        "bpobppbpqbprbpsbptbpubpvbpwbpxbpybpzbqabqbbqcbqdbqfbqgbqibqjbqkbqlbqmbqobqpbqqbqrbqsbqtbqubqvbqw" +
        "bqxbqzbr brabrbbrcbrdbrfbrgbrhbribrjbrkbrlbrmbrnbrobrpbrqbrrbrsbrtbrubrvbrwbrxbrybrzbs bsabsbbsc" +
        "bsebsfbshbsibsjbskbslbsmbsnbsobspbsqbsrbssbstbsubsvbswbsxbsybtabtcbtdbtebtfbtgbthbtibtjbtmbtnbto" +
        "btpbtqbtrbtsbttbtubtvbtwbtxbtybtzbuabubbucbudbuebufbugbuhbuibujbukbumbunbuobupbuqbusbutbuubuvbuw" +
        "buxbuybuzbvabvbbvcbvdbvebvfbvgbvhbvibvjbvkbvmbvnbvobvqbvrbvtbvubvvbvwbvxbvybvzbwabwbbwcbwdbwebwf" +
        "bwgbwhbwibwjbwkbwlbwmbwobwpbwqbwrbwsbwtbwubwwbwxbwybwzbxabxbbxcbxfbxgbxhbxibxjbxlbxmbxnbxobxpbxq" +
        "bxsbxubxvbxwbxzbyabybbycbydbyebyfbyhbyibyjbykbylbymbynbypbyrbysbyvbywbyxbyzbzabzbbzcbzdbzebzfbzh" +
        "bzibzjbzkbzlbzmbznbzobzpbzqbzrbztbzubzvbzwbzxbzybzzca caacabcaccadcaecafcagcahcajcakcalcamcancao" +
        "capcaqcarcascavcawcaxcaycazcbbcbccbdcbgcbicbjcbkcblcbncbocbqcbrcbscbtcbucbvcbwcbycccccdcceccgcch" +
        "ccjcclccmccoccpccrcdecdfcdhcdicdjcdmcdocdrcdzce ceacebcegcekcencetceycfacfdcfgcfmcgacgccggcgkch " +
        "chbchdchfchgchhchjchkchlchmchnchochpchqchrchtchwchxchychzciacibcicciecihcimcincipcirciwciycjacje" +
        "cjhcjicjkcjmcjncjocjpcjscjvcjyckbcklckmcknckockqckrckscktckuckvckxckyckzclaclccleclhclicljclkcll" +
        "clmclocltcluclwclycmacmecmgcmicmlcmocmrcmscmtcnacnbcnccngcnhcnicnkcnlcnpcnqcnscntcnwcnxco coacob" +
        "coccodcoecofcogcohcojcokcolcomcoocopcoqcotcoucoxcozcpacpbcpccpgcpicpncpocpscpucpxcpycqdcr cracrb" +
        "crccrdcrfcrgcrhcricrjcrkcrlcrmcrncrocrqcrscrtcrvcrwcrxcrycrzcs csacsbcshcsjcskcsmcsocspcsscstcsv" +
        "cswcsycszctactcctdctectgcthctlctmctnctoctpctscttctuctyctzcu cuacubcuccuhcuicujcukculcuocupcutcuu" +
        "cuvcuxcuycv cvgcvncwacwbcwecwgcwtcxhcy cyacybcyoczhczkczncztda daadacdaddaedagdahdaidajdakdaldam" +
        "daodaqdardasdaudavdawdaxdazdbadbbdbddbedbfdbgdbidbjdbldbmdbndbodbpdbqdbtdbudbvdbwdbydccdcrddaddd" +
        "ddeddgddiddjddnddoddrddsddwde decdeddeedefdegdehdeidekdeldemdendeqderdesdevdezdgadgbdgcdgddgedgg" +
        "dghdgidgkdgldgndgrdgsdgtdgwdgxdgzdhgdhidhldhmdhndhodhrdhsdhudhvdhwdhxdiadibdicdiddifdigdihdiidij" +
        "dildindiodipdirdisdiudiwdixdiydizdjadjbdjcdjddjedjfdjidjjdjkdjmdjndjodjrdjudjwdkadkgdkkdkrdksdkx" +
        "dlgdlmdlndmadmbdmcdmddmedmfdmgdmkdmldmmdmodmrdmsdmudmvdmwdmxdmydnadnddnedngdnidnjdnkdnndnodnrdnt" +
        "dnudnvdnwdnydoadobdocdoedofdohdoidokdoldondoodopdordosdotdovdowdoxdoydppdrcdredrgdridrldrndrodrq" +
        "drsdrtdrudrydsbdshdsidskdsndsodsqdtadtbdtddthdtidtkdtmdtodtpdtrdtsdttdtudtyduadubducduedufdugduh" +
        "duidukduldumdunduodupduqdurdusduuduvduwduxduyduzdv dvadwadwkdwrdwsdwudwwdwydwzdyadybdyddygdyidym" +
        "dyndyodyrdyudyydz dzadzddzedzgdzldzneaaebcebgebkeboebrebuecrecyee efaefeefiegaeglegmegoegyehueip" +
        "eiteivejaekaekeekgekieklekmekoekpekrekyel eleelkelmeloeluemaembemeemgemiemmemnempemsemuemwemxemz" +
        "en enaenbencendenfenhenlenmennenoenqenrenvenwenxeo eotepieraergerherierkerrerserterwes eseesgesh" +
        "esiesmessesuesyet etbetnetoetretsettetuetxetzeu eudeveevhevnewoexteyaeyoezaezefa faafabfadfaffag" +
        "fahfaifajfakfalfamfanfapfarfaufaxfayfazfblferff ffiffmfgrfi fiafiefiffilfipfirfitfiwfj fkkfkvfla" +
        "flhflifllflnflrflyfmpfmufnbfngfnifo fodfoifomfonforfosfpefqsfr frcfrdfrkfrmfrofrpfrqfrrfrsfrtfub" +
        "fudfuefuffuhfuifumfunfuqfurfutfuufuvfuyfvrfwafwefy ga gaagabgacgadgaegafgaggahgaigajgakgalgamgan" +
        "gaogapgaqgargasgatgaugawgaxgaygbagbbgbdgbegbfgbggbhgbigbjgbkgblgbmgbngbpgbqgbrgbsgbugbvgbwgbxgby" +
        "gbzgccgcdgcfgclgcngcrgctgd gdbgdcgddgdegdfgdggdhgdigdjgdkgdlgdmgdngdogdqgdrgdtgdugdxgeagebgecged" +
        "gefgeggehgeigejgekgelgeqgesgevgewgexgeygezgfkggaggbggdggegggggkgglggtgguggwghaghcgheghkghnghoghr" +
        "ghsghtgiagibgicgidgiegiggihgilgimgingipgiqgirgisgitgixgiygizgjkgjmgjngjrgjugkagkdgkegkngkogkpgku" +
        "gl glbglcgldglhgljglkgllgloglrgluglwgmagmbgmdgmggmhgmlgmmgmngmrgmugmvgmxgmygmzgn gnagnbgncgndgne" +
        "gnggnhgnignjgnkgnlgnmgnngnqgnrgntgnugnwgnzgoagobgocgodgoegofgoggohgoigojgokgolgongoogopgoqgorgos" +
        "gotgougovgowgoxgoygpagpegpngqagqngqrgragrbgrcgrdgrggrhgrigrjgrmgrqgrsgrtgrugrvgrwgrxgrygrzgslgsn" +
        "gsogspgswgtagtugu guagubgucgudguegufguhguigukgulgumgunguogupguqgurgutguuguwguxguzgv gvagvcgvegvf" +
        "gvjgvlgvmgvngvogvpgvrgvsgvygwagwbgwcgwdgwegwfgwggwigwjgwmgwngwrgwtgwugwwgwxgxxgybgydgyegyfgyggyi" +
        "gylgymgyngyogyrgyygyzgzagzigznha haahachadhaehaghahhaihajhakhalhamhanhaohaphaqharhashavhawhaxhay" +
        "hazhbahbbhbnhbohbuhchhdyhe hedheghehheihemhgmhgwhhihhrhhyhi hiahibhidhifhighihhiihijhikhilhiohir" +
        "hithiwhixhjihkahkehkhhkkhlahlbhldhlthluhmahmbhmdhmfhmjhmmhmnhmphmqhmrhmshmthmuhmvhmwhmyhmzhnahnd" +
        "hnehnghnhhnihnjhnnhnohnsho hoahobhochodhoehohhoihojholhomhoohophorhothovhowhoyhpohr hrahrchrehrk" +
        "hrmhrohrphrthruhrwhrxhrzhsbhsnhssht htihtohtshtuhtxhu hubhuchudhuehufhughuhhuihukhulhumhuphurhus" +
        "huthuuhuvhuwhuxhuyhuzhvchvehvkhvnhvvhwahwchwohy hyahywhz ia iaiianiaribaibbibdibeibgibhiblibmibn" +
        "ibribuibyicaichicrid idaidbidciddideidiidridsidtiduie ifaifbifeiffifkifmifuifyig igbigeiggigligm" +
        "ignigoigsigwihbihiihpihwii iinijcijeijjijnijsik ikhikiikkiklikoikpikriktikvikwikxikzilailbilgili" +
        "ilkilmiloilpiluilvimiimlimnimoimrimsimtimyin inbinginhinjinninoinpintio ioriouiowipiipoiquiqwire" +
        "irhiriirkirniruirxiryis isaiscisdishisiiskismisnisoistisuit itbitditeitiitkitlitmitoitritsittitv" +
        "itwitxityitziu iumivbivviw iwkiwmiwoiwsixcixliyaiyoiyxizhizmizrizzja jaajabjacjadjaejafjahjajjak" +
        "jaljamjanjaojaqjasjatjaujaxjayjazjbejbijbjjbkjbmjbnjbojbrjbtjbujbwjctjdajdgjdtjebjeejehjeijekjel" +
        "jenjerjetjeujgbjgejgkjgojhiji jiajibjicjidjiejigjiljimjitjiujivjiyjjejjrjkajkmjkojkujlejmajmbjmc" +
        "jmdjmijmljmnjmrjmsjmwjmxjnajndjngjnijnjjnljnsjobjodjogjorjowjpajprjqrjrajrbjrrjrtjrujuajubjudjuh" +
        "juijukjuljumjunjuojupjurjutjuujuwjuyjv jvdjvnjw jwijyajyejyyka kaakabkackadkagkahkaikajkakkamkao" +
        "kapkaqkavkawkaxkaykbakbbkbckbdkbekbgkbhkbikbjkbkkblkbmkbnkbokbpkbqkbrkbskbtkbukbvkbwkbxkbykbzkca" +
        "kcbkcckcdkcekcfkcgkchkcikcjkckkclkcmkcnkcokcpkcqkcskctkcukcvkcwkcykczkdakdckddkdekdfkdgkdhkdikdj" +
        "kdkkdlkdmkdnkdpkdqkdrkdtkdwkdxkdykdzkeakebkeckedkeekefkegkehkeikekkelkemkenkeokerkesketkeukevkew" +
        "kexkeykezkfakfbkfckfdkfekffkfgkfhkfikfkkflkfmkfnkfokfpkfqkfrkfskfukfvkfwkfxkfykfzkg kgakgbkgekgf" +
        "kgjkgkkglkgokgpkgqkgrkgskgtkgukgvkgwkgxkgykhakhbkhckhdkhekhfkhgkhhkhjkhlkhnkhokhpkhqkhrkhskhtkhu" +
        "khvkhwkhxkhykhzki kiakibkickidkiekifkigkihkijkilkimkiokipkiqkiskitkiukivkiwkixkiykizkj kjakjbkjc" +
        "kjdkjekjgkjhkjikjjkjkkjlkjmkjnkjokjpkjqkjrkjskjtkjukjxkjykjzkk kkakkbkkckkdkkekkfkkgkkhkkikkjkkk" +
        "kklkkmkkokkpkkqkkrkkskktkkukkvkkwkkxkkykkzkl klaklbklckldkleklfklgklhklikljklkkllklmklnkloklpklq" +
        "klrklskltkluklvklwklxklyklzkm kmakmbkmckmdkmekmfkmgkmhkmikmjkmkkmlkmmkmnkmokmpkmqkmskmtkmukmvkmw" +
        "kmxkmykmzkn knaknbkndkneknfkniknjknkknlknmknnknoknpknqknrknskntknuknvknwknxknyknzko koakockodkoe" +
        "kofkogkohkoikokkolkookopkoqkoskotkoukovkowkoykozkpakpckpdkpekpfkpgkphkpikpjkpkkplkpmkpnkpokpqkpr" +
        "kpskptkpukpwkpxkpykpzkqakqbkqckqdkqekqfkqgkqhkqikqjkqkkqlkqmkqnkqokqpkqqkqrkqskqtkqukqvkqwkqxkqy" +
        "kqzkr krakrbkrckrdkrekrfkrhkrikrjkrkkrlkrnkrpkrrkrskrtkrukrvkrwkrxkrykrzks ksbkscksdkseksfksgksh" +
        "ksiksjkskkslksmksnksokspksqksrksskstksuksvkswksxkszktaktbktcktdktektfktgkthktiktjktkktlktmktnkto" +
        "ktpktqktskttktuktvktwktxktyktzku kubkuckudkuekufkugkuhkuikujkukkulkumkunkuokupkuqkuskutkuukuvkuw" +
        "kuxkuykuzkv kvakvbkvckvdkvekvfkvgkvhkvikvjkvlkvmkvnkvokvpkvqkvrkvtkvvkvwkvxkvykvzkw kwakwbkwckwd" +
        "kwekwfkwgkwhkwikwjkwkkwlkwmkwnkwokwpkwrkwskwtkwukwvkwwkwykwzkxakxbkxckxdkxfkxikxjkxkkxmkxnkxokxp" +
        "kxqkxrkxtkxvkxwkxxkxykxzky kyakybkyckydkyekyfkygkyhkyikyjkykkylkymkynkyokyqkyrkyskytkyukyvkywkyx" +
        "kyykyzkzakzbkzckzdkzekzfkzikzkkzlkzmkznkzokzpkzrkzskzukzvkzwkzxkzykzzla laalablacladlaelaglahlai" +
        "lajlallamlanlaplaqlarlaslaulawlaxlazlb lbblbelbflbilbjlbllbmlbnlbolbqlbrlbtlbulbvlbwlbxlbylbzlcc" +
        "lcdlcelcflchlcllcmlcplcqlcsldaldblddldgldhldildjldkldlldmldnldoldpldqlealeblecledleeleflehleilej" +
        "leklellemlenleolepleqlerlesletleulevlewlexleylezlfalfnlg lgalgblgglghlgilgklgllgmlgnlgolgqlgrlgt" +
        "lgulgzlhalhhlhilhmlhnlhslhtlhuli lialibliclidlielifliglihlijliklilliolipliqlirlisliulivliwlixliy" +
        "lizljaljeljiljlljpljwljxlkalkblkclkdlkelkhlkilkjlkllkmlknlkolkrlkslktlkulkyllallbllclldllellfllg" +
        "llilljllklllllmllnllpllqllullxlmalmblmclmdlmelmflmglmhlmilmjlmklmllmnlmolmplmqlmrlmulmvlmwlmxlmy" +
        "ln lnalnblndlnglnhlnilnjlnllnmlnnlnslnulnwlnzlo loaloblocloeloglohloilojloklollomlonlooloploqlor" +
        "loslotloulowloxloylozlpalpelpnlpolpxlqrlralrclrglrilrklrllrmlrnlrolrtlrvlrzlsalsdlselsilsmlsrlss" +
        "lt ltcltglthltiltnltoltsltulu lualucludluelufluilujluklullumlunluolupluqlurluslutluuluvluwluyluz" +
        "lv lvalvilvklvllvulwalwelwglwhlwllwmlwolwtlwwlxmlyalynlzhlzllznlzzmaamabmadmaemafmagmaimajmakmam" +
        "manmaqmasmatmaumavmawmaxmazmbambbmbcmbdmbfmbhmbimbjmbkmblmbmmbnmbombpmbqmbrmbsmbtmbumbvmbwmbxmby" +
        "mbzmcamcbmccmcdmcemcfmcgmchmcimcjmckmclmcmmcnmcomcpmcqmcrmcsmctmcumcvmcwmcxmcymczmdamdbmdcmddmde" +
        "mdfmdgmdhmdimdjmdkmdmmdnmdpmdqmdrmdsmdtmdumdvmdwmdxmdymdzmeamebmecmedmeemehmejmekmelmemmenmeomep" +
        "meqmermesmetmeumevmewmeymezmfamfbmfcmfdmfemffmfgmfhmfimfjmfkmflmfmmfnmfomfpmfqmfrmftmfumfvmfwmfx" +
        "mfymfzmg mgamgbmgcmgdmgemgfmggmghmgimgjmgkmglmgmmgnmgomgpmgqmgrmgsmgtmgumgvmgwmgymgzmh mhbmhcmhd" +
        "mhemhfmhgmhimhjmhkmhlmhmmhnmhomhpmhqmhsmhtmhumhwmhxmhymhzmi miamibmicmidmiemifmigmihmiimijmikmil" +
        "mimminmiomipmiqmirmitmiumiwmixmiymizmjbmjcmjdmjemjgmjhmjimjjmjkmjlmjmmjnmjqmjrmjsmjtmjumjvmjwmjx" +
        "mjymjzmk mkamkbmkcmkemkfmkimkjmkkmklmkmmknmkomkpmkrmksmktmkumkvmkwmkxmkymkzml mlamlbmlcmlemlfmlh" +
        "mlimljmlkmllmlnmlomlpmlqmlrmlsmlumlvmlwmlxmlzmmammbmmcmmdmmemmfmmgmmhmmimmmmmnmmommpmmqmmrmmtmmu" +
        "mmvmmwmmxmmymmzmn mnamnbmncmndmnemnfmngmnhmnimnjmnlmnmmnnmnpmnqmnrmnsmnumnvmnwmnxmnymnzmo moamoc" +
        "modmoemogmohmoimojmokmommoomopmoqmormosmotmoumovmowmoxmoymozmpampbmpcmpdmpempgmphmpimpjmpkmplmpm" +
        "mpnmpomppmpqmprmpsmptmpumpvmpwmpxmpympzmqamqbmqcmqemqfmqgmqhmqimqjmqkmqlmqmmqnmqomqpmqqmqrmqsmqu" +
        "mqvmqwmqxmqymqzmr mramrbmrcmrdmrfmrgmrhmrjmrkmrlmrmmrnmromrpmrqmrrmrsmrtmrumrvmrwmrxmrymrzms msb" +
        "mscmsemsfmsgmshmsimsjmskmslmsmmsnmsomspmsqmssmsumsvmswmsxmsymszmt mtamtbmtcmtdmtemtfmtgmthmtimtj" +
        "mtkmtlmtmmtnmtomtpmtqmtrmtsmttmtumtvmtwmtxmtymuamubmucmudmuemugmuhmuimujmukmummuomuqmurmusmutmuu" +
        "muvmuxmuymuzmvamvdmvemvfmvgmvhmvkmvlmvnmvomvpmvqmvrmvsmvtmvumvvmvwmvxmvymvzmwamwbmwcmwemwfmwgmwh" +
        "mwimwkmwlmwmmwnmwomwpmwqmwrmwsmwtmwumwvmwwmwzmxamxbmxcmxdmxemxfmxgmxhmximxjmxkmxlmxmmxnmxomxpmxq" +
        "mxrmxsmxtmxumxvmxwmxxmxymxzmy mybmycmyemyfmygmyhmyjmykmylmymmypmyrmyumyvmywmyxmyymyzmzamzdmzemzh" +
        "mzimzjmzkmzlmzmmznmzomzpmzqmzrmztmzumzvmzwmzxmzzna naanabnacnaenafnagnajnaknalnamnannaonapnaqnar" +
        "nasnatnawnaxnaynaznb nbanbbnbcnbdnbenbhnbinbjnbknbmnbnnbonbpnbqnbrnbtnbunbvnbwnbyncancbnccncdnce" +
        "ncfncgnchncincjncknclncmncnnconcqncrnctncuncxncznd ndandbndcnddndfndgndhndindjndkndlndmndnndpndq" +
        "ndrndsndtndundvndwndxndyndzne neanebnecnedneenegnehneinejneknemnenneoneqnernetneunewnexneyneznfa" +
        "nfdnflnfrnfung ngangbngcngdngenggnghngingjngknglngmngnngpngqngrngsngtngungvngwngxngyngznhanhbnhc" +
        "nhdnhenhfnhgnhinhknhmnhnnhonhpnhqnhrnhtnhunhvnhwnhxnhynhznianibnidnienifnignihniinijnilnimninnio" +
        "niqnirnisnitniunivniwnixniyniznjanjbnjdnjhnjinjjnjlnjmnjnnjonjrnjsnjtnjunjxnjynjznkankbnkcnkdnke" +
        "nkfnkgnkhnkinkjnkknkmnknnkonkqnkrnksnktnkunkvnkwnkxnkznl nlanlcnlenlgnlinljnlknlmnlonlqnlunlvnlw" +
        "nlxnlynlznmanmbnmcnmdnmenmfnmgnmhnminmjnmknmlnmmnmnnmonmpnmqnmrnmsnmtnmunmvnmwnmxnmznn nnannbnnc" +
        "nndnnennfnngnnhnninnjnnknnlnnmnnnnnpnnqnnrnntnnunnvnnwnnynnzno noanocnodnoenofnognohnoinojnoknon" +
        "nopnoqnosnotnounovnownoynpbnpgnphnplnpnnponpsnpunpxnpynqgnqknqlnqmnqnnqonqqnqtnqynr nranrbnrenrf" +
        "nrgnrinrknrlnrmnrnnrpnrunrxnrznsansbnscnsdnsensfnsgnshnsknsmnsnnsonsqnssnstnsunsvnswnsxnsynszntd" +
        "ntentgntintjntkntmntontpntrntuntxntyntznuanucnudnuenufnugnuhnuinujnuknumnunnuonupnuqnurnusnutnuu" +
        "nuvnuwnuxnuynuznv nvhnvmnvonwbnwcnwenwgnwinwmnwonwrnwwnwxnxanxdnxenxgnxinxlnxnnxonxqnxrnxxny nyb" +
        "nycnydnyenyfnygnyhnyinyjnyknylnymnynnyonypnyqnyrnysnytnyunyvnywnyxnyynzanzbnzdnzinzknzmnzrnzunzy" +
        "nzzoaaoacoaroavobiobkoblobmoboobrobtobuoc ocaocoocuodaodkodtoduofsofuogbogcoggogooguohtohuoiaoie" +
        "oinoj ojbojcojsojvojwokaokbokcokdokeokgokiokkokmokookroksokuokvokxokzolaoldoleolkolmoloolroltolu" +
        "om omaombomcomgomiomkomlomoompomromtomuomwomxonaoneongonionjonkonnonoonponronsontonuonxoodoonoor" +
        "opaopkopmopooptopyor oraorcoreorgornoroorrorsortoruorvorworxorzos osaoscosiosoospostosuosxotaotb" +
        "otdoteotiotkotlotmotnotqotrotsottotuotwotxotyotzouboueouioumovdowiowloydoymoyyozmpa pabpacpadpae" +
        "pafpagpahpaipakpalpampaopappaqparpaspaupavpawpaxpaypazpbbpbcpbepbfpbgpbhpbipblpbmpbnpbopbppbrpbs" +
        "pbtpbvpbypcapcbpccpcdpcepcfpcgpchpcipcjpckpcmpcnpcppcwpdapdcpdnpdopdtpdupeapebpedpeepegpeipekpel" +
        "pempeopeppeqpevpexpeypezpfapfepflpgapgdpggpgipgkpglpgnpgspguphdphgphhphkphlphmphnphophrphtphuphv" +
        "phwpi piapibpicpidpifpigpihpijpilpimpinpiopippirpispitpiupivpiwpixpiypizpjtpkapkbpkgpkhpknpkopkp" +
        "pkrpkupl plaplbplcpldpleplgplhplkpllplnploplrplspluplvplwplzpmapmbpmdpmepmfpmhpmipmjpmlpmmpmnpmo" +
        "pmqpmrpmspmtpmwpmxpmypmzpnapncpndpnepngpnhpnipnjpnkpnlpnmpnnpnopnppnqpnrpnspntpnvpnwpnypnzpocpoe" +
        "pofpogpohpoipokpomponpoopoppoqpospotpovpowpoyppeppippkpplppmppnppopppppqppspptpqapqmpraprcprdpre" +
        "prfprgprhpriprkprmproprqprrprtpruprwprxps psapsepshpsipsmpsnpsqpsspstpsupswpt ptapthptiptnptoptp" +
        "ptrpttptuptvpuapubpucpudpuepufpugpuipujpumpuopuppuqpurputpuupuwpuxpuypwapwbpwgpwmpwnpwopwrpwwpxm" +
        "pyepympynpyupyxpyypzepzhpznqu quaqubqucqudqufqugquiqukqulqumqunqupquqqurqusquvquwquxquyqvaqvcqve" +
        "qvhqviqvjqvlqvmqvnqvoqvpqvsqvwqvzqwaqwcqwhqwmqwsqwtqxaqxcqxhqxlqxnqxoqxpqxqqxrqxtqxuqxwqyaqypraa" +
        "rabracradrafragrahrairajrakramranraoraprarravrawraxrayrazrbbrbkrblrbprcfrdbrearebreeregreirejrel" +
        "remrenresretreyrgargnrgrrgsrgurhgrhpriarifrilrimrinrirritriurjgrjirjsrkarkbrkhrkirkmrktrkwrm rma" +
        "rmbrmcrmdrmermfrmgrmhrmirmkrmlrmmrmnrmormprmqrmtrmurmwrmxrmzrn rndrngrnlrnnrnrrnwro robrocrodroe" +
        "rofrogrolromrooroprorrourowrpnrptrrirrmrrorrtrskrswrtcrthrtmrtwru rubrucruerufrugruirukruorupruq" +
        "rutruuruyruzrw rwarwkrwlrwmrworwrrxdrxwryusa saasabsacsadsaesafsahsajsaksamsaosaqsarsassatsausav" +
        "sawsaxsaysazsbasbbsbcsbdsbesbgsbhsbisbjsbksblsbmsbnsbosbpsbqsbrsbssbtsbusbvsbwsbxsbysbzsc scbsce" +
        "scfscgschsciscksclscnscoscpscssctscuscvscwscxsd sdasdbsdcsdesdfsdgsdhsdjsdksdnsdosdqsdrsdssdusdx" +
        "se seasebsecsedseesefsegsehseisejsekselsenseosepseqsersessetseusevsewseysezsfesfmsfwsg sgasgbsgc" +
        "sgdsgesghsgisgjsgmsgpsgrsgssgtsgusgwsgysgzshashbshcshdsheshgshhshishjshkshmshnshoshpshqshrshssht" +
        "shushvshwshyshzsi siasibsidsiesifsigsihsiisijsiksilsimsipsiqsirsissiusivsiwsixsiysizsjasjbsjdsje" +
        "sjgsjlsjmsjpsjrsjtsjusjwsk skaskbskcskdskeskfskgskhskiskjskmsknskoskpskqskrskssktskuskvskwskxsky" +
        "skzsl slcsldslgslhslisljsllslmslnslpslrsluslwslxslyslzsm smasmbsmcsmfsmgsmhsmjsmksmlsmnsmpsmqsmr" +
        "smssmtsmusmwsmxsmysmzsn sncsnesnfsngsnisnjsnksnlsnmsnnsnosnpsnqsnrsnssnusnvsnwsnxsnysnzso soasob" +
        "socsodsoesogsoisoksolsoosopsoqsorsossousovsowsoxsoysozspbspcspdspespgspispksplspmspnsposppspqspr" +
        "spssptspvsq sqasqhsqmsqosqqsqtsqusr srasrbsresrfsrgsrhsrisrksrlsrmsrnsrosrqsrrsrssrtsrusrvsrwsrx" +
        "srysrzss ssbsscssdssessfssgsshssjsslssmssnssossqssssstssussvssxssysszst stastbstestfstgsthstistj" +
        "stkstlstmstnstostpstqstrstssttstvstwstysu suasubsucsuesugsuisujsuksuosuqsursussutsuvsuwsuysuzsv " +
        "svasvbsvcsvesvmsvssw swbswfswgswiswjswkswmswoswpswqswrswsswtswuswvswwswxswysxbsxesxnsxrsxssxusxw" +
        "syasybsycsyisyksylsymsynsyosyrsyssywsyxszaszbszcszgszlsznszpszvszwszyta taatabtactadtaetaftagtaj" +
        "taktaltantaotaptaqtartastautavtawtaxtaytaztbatbctbdtbetbftbgtbhtbitbjtbktbltbmtbntbotbptbstbttbu" +
        "tbvtbwtbxtbytbztcatcbtcctcdtcetcftcgtchtcitcktcmtcntcotcptcqtcstcutcwtcxtcytcztdatdbtdctddtdetdg" +
        "tdhtditdjtdktdltdmtdntdotdqtdrtdstdttdvtdxtdyte teatebtectedteetegtehteitektemtenteotepteqtertes" +
        "tetteutevtewtexteyteztfitfntfotfrtfttg tgatgbtgctgdtgetgftghtgitgjtgntgotgptgqtgstgttgutgvtgwtgx" +
        "tgytgzth thdthethfthhthithkthlthmthpthqthrthsthtthuthvthythzti tictiftigtihtiitijtiktiltimtintio" +
        "tiptiqtistittiutivtiwtixtiytjatjgtjitjjtjltjntjotjptjstjutjwtk tkatkbtkdtketkftkgtkltkptkqtkrtks" +
        "tkttkutkvtkwtkxtkztl tlatlbtlctldtlftlgtlitljtlktlltlmtlntlptlqtlrtlstlttlutlvtlxtlytmatmbtmctmd" +
        "tmetmftmgtmhtmitmjtmltmmtmntmotmqtmrtmttmutmvtmwtmytmztn tnatnbtnctndtngtnhtnitnktnltnmtnntnotnp" +
        "tnqtnrtnstnttnvtnwtnxtnyto tobtoctodtoftogtohtoitojtoktoltomtootoptoqtortostoutovtowtoxtoytoztpa" +
        "tpctpetpftpgtpitpjtpktpltpmtpntpptprtpttputpvtpxtpytpztqbtqltqmtqntqotqptqttqutqwtr tratrbtrctre" +
        "trftrgtrhtritrjtrltrmtrntrotrptrqtrrtrstrttrutrvtrwtrxtrytrzts tsatsbtsctsdtsgtshtsitsjtsltsptsr" +
        "tsttsutsvtswtsxtsztt ttbttcttdttettftthttittjttkttlttmttnttottpttrttstttttuttvttwttyttztuatubtuc" +
        "tudtuetuftugtuhtuitujtultumtuntuotuqtustuutuvtuxtuytuztvatvdtvetvitvktvltvmtvntvotvstvttvutvwtvx" +
        "twatwbtwdtwetwftwgtwhtwltwmtwntwotwptwqtwrtwttwutwwtwxtwytxatxetxgtxitxjtxmtxntxotxqtxstxttxutxx" +
        "txyty tyatyetyhtyityjtyltyntyptyrtystyttyutyvtyxtyytyztzhtzjtzltzmtzntzotzxuamuarubaubiublubrubu" +
        "ubyudaudeudgudiudjudludmuduuesufiug ugaugbugeughugouhauhnuisuivujiuk ukaukgukhukiukkukpukqukuukv" +
        "ukwukyulaulbulculeulfuliulkulmulnuluulwulyumaumbumdumgumiummumnumoumpumrumsunaunduneunguniunkunm" +
        "unnunrunuunxunzuonupiupvur uraurburcureurfurgurhuriurkurmurnurourpurrurturuurvurwurxuryurzusaush" +
        "usiuskuspussusuutauteuthutputrutuuumuuruveuvhuvluwauyauz uzsvaavaevafvagvahvaivajvalvamvanvaovap" +
        "varvasvauvavvayvbbvbkve vecvemveovepvervgrvi vicvidvifvigvilvinvitvivvjkvkavkjvkkvklvkmvknvkovkp" +
        "vktvkuvkzvlpvlsvmavmbvmcvmdvmevmfvmgvmhvmivmjvmkvmlvmmvmpvmqvmrvmsvmuvmwvmxvmyvmzvnkvnmvnpvo vor" +
        "votvravrovrsvrtvtovumvunvutvwawa waawabwacwadwaewafwagwahwaiwajwalwamwanwapwaqwarwaswatwauwavwaw" +
        "waxwaywazwbawbbwbewbfwbhwbiwbjwbkwblwbmwbpwbqwbrwbtwbvwbwwcawciwddwdgwdjwdkwdtwduwdywecwedwegweh" +
        "weiwemweowepwerweswetweuwewwfgwgawgbwggwgiwgowguwgywhawhgwhkwhuwibwicwiewifwigwihwiiwijwikwilwim" +
        "winwirwiuwivwiywjawjiwkawkdwkrwkwwkywlawlewlgwlhwliwlmwlowlrwlswluwlvwlwwlxwmawmbwmcwmdwmewmhwmi" +
        "wmmwmnwmowmswmtwmwwmxwnbwncwndwnewngwniwnkwnmwnnwnownpwnuwnwwnywo woawobwocwodwoewofwogwoiwokwom" +
        "wonwooworwoswowwpcwrbwrgwrhwriwrkwrlwrmwrowrpwrrwrswruwrvwrwwrxwrzwsawsgwsiwskwsrwsswsuwsvwtbwtf" +
        "wthwtiwtkwtmwtwwuawubwudwulwumwunwurwutwuuwuvwuxwuywwawwbwwowwrwwwwxwwybwyiwymwynwyrwyyxaaxabxag" +
        "xaixajxakxalxamxanxaoxarxasxatxauxavxawxayxbbxbdxbexbgxbixbjxbmxbnxbpxbrxbwxbyxchxcoxcrxdaxdkxdo" +
        "xdqxdyxedxegxemxerxesxetxeuxgbxgdxggxgixgmxguxgwxh xhexhmxhvxiixinxirxisxiyxjbxjtxkaxkbxkcxkdxke" +
        "xkfxkgxkjxklxknxkpxkqxkrxksxktxkuxkvxkwxkxxkyxkzxlaxlcxldxlyxmaxmbxmcxmdxmfxmgxmhxmjxmmxmnxmoxmp" +
        "xmqxmrxmtxmuxmvxmwxmxxmyxmzxnaxnbxnixnjxnkxnmxnnxnqxnrxntxnuxnyxnzxocxodxogxoixokxomxonxooxopxor" +
        "xowxpaxpbxpdxpfxpgxphxpixpjxpkxplxpmxpnxpoxpqxprxptxpvxpwxpxxpzxraxrbxrdxrexrgxrixrmxrnxrrxruxrw" +
        "xsaxsbxsexshxsixsmxsnxspxsqxsrxsuxsyxtaxtbxtcxtdxtexthxtixtjxtlxtmxtnxtpxtqxtsxttxtuxtvxtwxtyxub" +
        "xudxujxulxumxunxuoxutxuuxvexvixvnxvoxvsxwaxwdxwexwjxwkxwlxwoxwrxwtxwwxxbxxkxxmxxrxxtxyaxybxyjxyk" +
        "xylxytxyyxzhxzpyaayabyacyadyaeyafyagyahyaiyajyakyalyamyanyaoyapyaqyaryasyatyauyavyawyaxyayyazyba" +
        "ybbybeybhybiybjyblybmybnyboybxybyyclycnycrydaydeydgydkyeayecyeeyeiyejyelyeryesyetyeuyevyeyygaygi" +
        "yglygmygpygryguygwyhdyi yiayigyihyiiyijyilyimyiryisyivykaykgykhykiykkykmykoykrykyylaylbyleylgyli" +
        "yllylryluylyymbymeymgymkymlymmymnymoympynayndyngynkynlynqynsynuyo yobyogyoiyokyolyomyonyotyoyyra" +
        "yrbyreyrkyrlyrmyroyrsyrwyryysdysnyspysryssysyytwytyyuayubyucyudyueyufyugyuiyujyulyumyunyupyuqyur" +
        "yutyuwyuxyuzyvayvtywaywgywnywqywrywuywwyxayxgyxlyxmyxuyxyyyryyuza zaazabzaczadzaezafzagzahzajzak" +
        "zamzaozapzaqzarzaszatzauzavzawzaxzayzazzbazbczbezbtzbuzbwzcazchzdjzeazegzehzemzenzgazgbzghzgmzgn" +
        "zgrzh zhdzhizhnzhwzhxziazikzilzimzinziwzizzkazkdzkozkpzktzkuzkzzlazljzlmzlnzlqzluzmazmbzmczmdzme" +
        "zmfzmgzmhzmizmjzmkzmlzmmzmnzmozmpzmqzmrzmszmtzmuzmvzmwzmxzmyzmzznaznezngznkznszoczohzomzoozoqzor" +
        "zoszpazpbzpczpdzpezpfzpgzphzpizpjzpkzplzpmzpnzpozppzpqzprzpszptzpuzpvzpwzpxzpyzpzzqezrgzrnzrozrp" +
        "zrszsazsrzsuzteztgztlztmztnztpztqztszttztuztxztyzu zuhzumzunzuyzwazygzyjzynzypzzazzj"

    /** The [SCRIPT_CODES] index of each tag, two hexadecimal digits each. */
    private val LANGUAGE_SCRIPTS: String =
        "37373737373743373737373737023737371e373737371437373737373737023758373737373737373702373737373737" +
        "373737370237370237373737370202373737373737023737373737373737373737377014370537020202373737023737" +
        "373737370237373737373737373737373737373737373737151837373737373737373737373714373737371837373737" +
        "373701371537373702373737373764253737372c48370237373737373737373737373737373737373737377737373737" +
        "3737373714373737373737373737373443373737373714143718373737183737373737373737373737373737372a3737" +
        "376437373737373737373737373714373737373737151515373718373737373737373737373737373737373737373709" +
        "373737020237373715373737373737373737373737373737373714373737373737373702033737373737373737373702" +
        "37023737370202093737375e373737370237373715373737373737373737373737373737373737023737373737373714" +
        "373737373737373737370237373737373737373737373702143702373702373737373737371537373737373737371837" +
        "373737373737373737373737370437373737373737370237370202370237373737373702373737373737371437373737" +
        "373737370237371537373737370637373737373737373737371937373737373737373737373737373737373737373737" +
        "373737373718373737373737373737373737373737373737373737373737373737373750373737021437373737153737" +
        "370237373737373737373737373737371537373737373737373737373769370270503715151437371515373737370237" +
        "02153737373737151e373715153715023737141515370264153737373715153737373737373737373737373737373737" +
        "373737373737153737373764373737153737023737373737373737373737373737373737373737703737373737373737" +
        "3737373737373737373737373737373748373737373737376b3737373737373737373737373737371537373737373737" +
        "373737373737370937373737373737373737373737373737153737373737377037373737373737373737373737373737" +
        "373737373737373737371437373737373737373737373737371509373737373737370237373737373737373737373737" +
        "373737152f37153737023737023737377037373737373734311537373737373737370237370237373737370737371837" +
        "373737373737083737373737370837373737373737371537373737143737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737373737373737373737373737483737373737373737373737373737373737" +
        "373737373737373737373714373737373745373737373737373737153737373737183737373715373737373737373737" +
        "6f3737373737373737373737373737373737373737373737373737373737373737373737373737373737373737373737" +
        "376f3737373737373737373737373737373737370c376c37151f15152337091437373737373737373737373737377037" +
        "37373702373737371437373737103737153737373737371537373737373702373714370f373737373723023737373737" +
        "373714373737373737373702373737373737373714373737623737373737377037373737373737233737373737373737" +
        "373737376f3737373737371237373737373737371e37373737373737370d37373737373714370d0d0d0d373737373737" +
        "373737373737374837373737233737370d37373737533709373737153737376937693714373737373737373737373733" +
        "373737143737373737373737373737372325373737373737373737373737373737151437373737373737373737373737" +
        "373737373737373737373737023737373737373737143737373737373702370237373737373709373737373737373737" +
        "3737370237373737373737371537371f1537373737153737373737373737373737373737373737373737373737373737" +
        "373737373737373737377037373737371437373737373737413702023737373737373737373737371437373737373737" +
        "484837373737373737371537373737373737373737183737377037373737371518373715373737373750373737373737" +
        "373737373737373715371f3737373715373737373737373737153737373737376e373750373737373715373737373737" +
        "373737373770373737377037373737373737371e1337373737373737371637373737373737373737373737372b1e3737" +
        "373737373737153737373737153737373737373737141437373737373737373737373769373737373737373737371b02" +
        "373737373737373737373728373737373714371437373737373702373737373737373737373737373737370202373737" +
        "373737370237373737373737373737373737373737373715373737373737373737373737373737373737373737373702" +
        "3737373737373737373737373737373737373737373737373737373737373723373750371f376c373737373737373737" +
        "373750151f15373737373737373737370237373737373737375037373737373737373737373714373737371537373737" +
        "3737373737373737373737373718373737373702373737373702371537376d0237703737373737023737371437373737" +
        "373737370237373702373737373737373737371402370237373737373737373737353737373718373a37373737373737" +
        "37373737373737373737373737373737373770183737371515371537373737371c373737373737373737373715371337" +
        "3737373737373709183737373737373737373737371f3737373737373737373737373737373737373737373737373737" +
        "373737373737153737373702373702373737373737023737373737373737373737373715373737370237373702373737" +
        "373737233737373737371837373737370237373725373718253737373737373737373715373737153737663737373737" +
        "77373737373702373715373726373756370a3737370a3737373737373737370215373737273702373737371537370237" +
        "153737373737373722154837373737373737376437373702372302373737373777373737373737373737373737373737" +
        "153737373725143737373737373737043704373737373737373737373737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737373737373737783737373737373737373737373737373737373737373737" +
        "373737373737373737373737373c37373714373737374837183737373737373737373737693737373737373737023737" +
        "37373737373737372514373737373737373737370d37373725373737373737373737373737372a373737023737373737" +
        "373737373737023737373725373737370237373737371470021437153737373737373737371937373725373737373737" +
        "373737373737213737483737373737373737153737373737660237373715153737023737252537372537373737373737" +
        "37371537503737373737375037373737377025371914373737373737373737371437372d373737373714377037373737" +
        "373737373737373737023737370237143737373737373737373737373737373737373737370237373737373737373737" +
        "373737373709376f373737373737373737373737373737373737373714374337156c3731156c31693731436915370237" +
        "37151515151537371515373737373737153737373737373737373737371537683737376f70373737150b373737374837" +
        "14023737373737373737371537373737143715373737373737373737373737373737341437373715373715483737376f" +
        "373737701437373737377037333737373737373737373715373737373737373737373715373737370237373737373737" +
        "1537373737373737372f3737373737373737371537373737373737373737373737370231373737373737373737371537" +
        "373737373737373737373732373737373737371415373737373737373737373737373737373737373737373737373737" +
        "37143737371437373737643737373737373737373737373737373737373737183737153714373737373737143737372f" +
        "3737152f3737373702373737373737373737373737373737373737374837483715371837371537373737373702373737" +
        "563737373737373737373737373737343737373737371437373737373737373737373714143737373737373737373737" +
        "3737374837483737022b373737373737373737373737373737373737373737373737373737373737483737486f373702" +
        "373737373737373714373737373737373737373737373737373737372b15153737373737373737373737373737373737" +
        "373737373737373737393725153702373737373737373737373737373737141537703715373437153737373737373737" +
        "3737373737376f3737373737373737373737373737373737373737373737373737373737373837373737373737373714" +
        "3737373737373737373737373737373737373737371537643737373737373737153737373737373737373b3737373737" +
        "373737373737373737373737377002373737373737373737373737373737373737373737373737373737373737373737" +
        "3715373737376c3737373737373737373737373737373737373737373737373437373737373737373737373737373737" +
        "373737373715373737375637373702373702023737373737370225373737370237243737373737373737373737373737" +
        "37703737373737373737371502373702373737373737373737376f6f3737373770372337373737373737371515373737" +
        "373737373737373737373737373737373737373737373737373737373737370237373737373737373737373737373737" +
        "373737373737373737373737373737021437373737373737373737373737373718183737373737373737373737373737" +
        "373737373737373737023737373737373702373737373737373737373737373737373736373737373737373737373737" +
        "3737153737373737373737373737373737373737023737373737373737373737373737373737373e3737373737373737" +
        "373737373737373737373737373737373737373737153737434337156c4337373715143715371537023737376f373737" +
        "37373737373737373743373737376f373737373737373737373737373737373737373737373737373737373737373737" +
        "373737373714373745373737373709023737373737371437374837373737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737373737373737373737376f37373737373737373737373737373737373737" +
        "3737373737156f3737153737371437373737463737153737373737373737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737143737373715373737373737373737371437373737377037373737371537" +
        "693737183737024537373737373737373737373737373702183737373737373737373737373737371537483737273737" +
        "373737373737373737373737373737373737373737373737374837373737373737373718373737143737373e37373737" +
        "373737373702373737373737373737373737373737373737373737231537373737373737373737373737373737373737" +
        "373737373737373737373737373715373737373737373737373734373737373737373737371437373737373737373737" +
        "373737373737373737153737373737147077373737373737373737153737373737373737373737373737373737373737" +
        "373737373737343737373737373737373737373737373737373737373737373737373737373737373737373737373714" +
        "3737376c3714373737373737373737373737373737373737373737373737373737373737373737373737373737373737" +
        "373737373737370237370237373737371537373737373737373737373737371537373737373737373737373737373737" +
        "373737373737373737373775373737373737373737373733153714371537375a37377837373737377037373737373737" +
        "373737373737374b373737373737373737373737375a3737373737373778377837370d37373737377137783737373737" +
        "3737373737373737373737780237373737373737373737373737373737373737373737373737373737374a3737373737" +
        "373715373737373737373737373737373737373737373737376f373737370237373737376f3737373737373737373737" +
        "3714146419373737553748373737373737370237373737373737373777373737370d37370d3737373737373737373721" +
        "2237373737372f1537703737373737373737373737371437374744373737483737373737373737373737373737371537" +
        "3737373737375037373737373737376c021437373714512829373737373702703737374f373737373737373737371d37" +
        "373752373737373737373720373737373737373737543737371437373737373737373737373737373737373737373737" +
        "023737372f373748434315156c373737373737373737373737373737503737373776373737373737373737372e153737" +
        "4d2837371537374802375534026f6f02155f37373737373737373737373737373737373737373737370b373737373737" +
        "433737373737373737370248373737373737373737373737370b37373737373737373737373737373737373737373737" +
        "3737373737373737371e373737373737373737373737373737373737373737373737373737373737373737372e020237" +
        "3737373737373737376f373702023737020237373737020b373737373737373737373737373737373737373737153737" +
        "37373737373737373737373748156f373737373748373737373737373737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737373737373737023737373737371515373715370937153737373737371537" +
        "3737374837373737023737373750373737373737373737373737593737373737373737373715152f3737483709373737" +
        "373737373737370437373737373737023737374837373737373737373737373737373737373737373737373737373714" +
        "37373737151437371437373737373737143737373737373737371537372c153737373737371437375b373737374e3737" +
        "3737375d3737373737373737373737370237373737373770373737373737373737373737150237371537346637371e02" +
        "3702373702020237373737370902373737373737373737373737373714373737373737373737373737375637374d3737" +
        "3737143715373702377037180237373737023737376d3737024837373737373702023737375f14373737373737373737" +
        "37377037373737373737020237371437373737153714373737376f373737373737371537373737370237373737373737" +
        "37373737373737373737373737373737373737373737373778373737375b373737372f37370237373737373737373737" +
        "37373737373737373737373737376b373737376015373737373737376f37373737373737373737373737373737373737" +
        "377050373737370234023714376137373702373737373737373737373737371537023737373737373702373737373737" +
        "343737373737373737373737373737373737373737373737023718371437373737373737373737373737373737376337" +
        "1937373737373702373722373737373737373737371537373737373737375a3737376437370937643764371537373737" +
        "373737373737376937143737373737153737373737373737373737373737373737373737373737653737373737373737" +
        "37373737373737373737373737373737704837373737376931376d15376737151537373737373737373737373737376c" +
        "373737373737373737373737373737293737373737373737373737371437373737157037373737373737373737373737" +
        "37376f371515376737156f37151515373737373718373718373715373737143737373737373737373737373737483702" +
        "373737373737153737373737373737021537373737373737373737373737373737373737373737373737373737373737" +
        "3737373737373737373737643737373737373737373737373737373737373737373737370c3737373737373737373737" +
        "37373737373737373737023737373737373737373737373737373737372f373737373737373737373737373702373737" +
        "3725373737370237373737373737373702373737373737371e3737377037373737373737373714373737373734373737" +
        "3737373437376f3737373737153737373737373737373737373737373737373737373737373737373748373737373737" +
        "3737373737373737153737373737373737373737376a37373737723737373737373737373737373737376b3737371437" +
        "37373737373737373737373737373737373714431437371437373702733737146f373737373714373737503737373737" +
        "373737371437373737373737373737373737373737373737373737373737373737093709373737370237373737373737" +
        "376f373737373737373737373737370237373737373737373737371e3737373737373702693702371574373737373737" +
        "371537151537373737373737370237373737373737373715373737373737373737373737373737373137373702373737" +
        "373737373737373737373737373737373737373737373737373737373737373737373737371837373737373737373737" +
        "37373737373737373737023737376c153737373737373737373737373737373737373737373737373737373737373737" +
        "373737373737373737373737373737373737373737373737373737373718373737370237373737373737373737153737" +
        "373737373737373737370237023737373737373737373737373737373737373737373737373737373737373737373737" +
        "373737373737371a37373737370237373737371537373737373737373723373737373737373737373737373737373700" +
        "373737143718373714373737373737373737373737373737373737110e37373714373737373737373737373737373737" +
        "37022f3737373750373737023702373770370237370237373737373737373737373c3d173737373719373737373f3737" +
        "374237373737373737493737373737373715373737373737373737373737373737373737371e374d3737371437373757" +
        "373737373737373737373714143737375c37373737373737371537373737373737373737373737370b37373737373769" +
        "376937373737373728023737373737373737371437373737373737373737373737373740373737373737373737143737" +
        "37373737373737373737373737373737373715153737373737373737373737370237433737371e373737376c37373737" +
        "373756373737252537782537373737373778371414373737373737373737373737373737373737373737373737563737" +
        "14373737373737372a37373737376f373737143737373737377878781437563737373737252437143737373737373737" +
        "373714373737373737563756373737373737373737373737373737373737373737373737373737703737373737023737" +
        "373737372202373722376d37226d22223723223737374c37373737373737373714373037143722372222373737373737" +
        "373737373737373737373737373737373737373737373737373737373737373737373737373737373737373737373737" +
        "3737373737373737373737225037372537373737373737373737373737373737373702373718223722373722"

    private val SCRIPT_CODES: Array<String> = arrayOf(
        "Aghb", "Ahom", "Arab", "Armi", "Armn", "Avst", "Bamu", "Bass", "Batk",
        "Beng", "Bopo", "Brah", "Cakm", "Cans", "Cari", "Cham", "Cher", "Chrs",
        "Copt", "Cprt", "Cyrl", "Deva", "Egyp", "Elym", "Ethi", "Geor", "Gong",
        "Gonm", "Goth", "Gran", "Grek", "Gujr", "Guru", "Hang", "Hani", "Hans",
        "Hant", "Hebr", "Hluw", "Hmnp", "Ital", "Java", "Jpan", "Kali", "Kana",
        "Kawi", "Khar", "Khmr", "Kits", "Knda", "Kore", "Lana", "Laoo", "Latf",
        "Latg", "Latn", "Lepc", "Lina", "Linb", "Lisu", "Lyci", "Lydi", "Mand",
        "Mani", "Marc", "Medf", "Merc", "Mlym", "Modi", "Mong", "Mroo", "Mtei",
        "Mymr", "Narb", "Newa", "Nkoo", "Nshu", "Ogam", "Olck", "Orkh", "Orya",
        "Osge", "Ougr", "Pauc", "Phli", "Phnx", "Plrd", "Prti", "Rjng", "Rohg",
        "Runr", "Samr", "Sarb", "Saur", "Sgnw", "Sinh", "Sogd", "Sora", "Soyo",
        "Sunu", "Syrc", "Tagb", "Takr", "Tale", "Talu", "Taml", "Tang", "Tavt",
        "Telu", "Tfng", "Thaa", "Thai", "Tibt", "Tnsa", "Toto", "Ugar", "Vaii",
        "Wcho", "Xpeo", "Xsux", "Yiii",
    )
}
