package it.cngei.assemblee.controllers;

import it.cngei.assemblee.dtos.AssembleaEditModel;
import it.cngei.assemblee.entities.Assemblea;
import it.cngei.assemblee.entities.Delega;
import it.cngei.assemblee.entities.Votazione;
import it.cngei.assemblee.repositories.AssembleeRepository;
import it.cngei.assemblee.repositories.DelegheRepository;
import it.cngei.assemblee.repositories.SocioRepository;
import it.cngei.assemblee.repositories.VotazioneRepository;
import it.cngei.assemblee.services.AssembleaService;
import it.cngei.assemblee.state.AssembleaState;
import it.cngei.assemblee.state.VotazioneState;
import it.cngei.assemblee.utils.Utils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.j256.twofactorauth.TimeBasedOneTimePasswordUtil.qrImageUrl;

@Controller
@RequestMapping("/assemblea")
public class AssembleaController {
  private final AssembleeRepository assembleeRepository;
  private final AssembleaService assembleaService;
  private final VotazioneRepository votazioneRepository;
  private final DelegheRepository delegheRepository;
  private final SocioRepository socioRepository;
  private final AssembleaState assembleaState;
  private final VotazioneState votazioneState;

  public AssembleaController(AssembleeRepository assembleeRepository, AssembleaService assembleaService, VotazioneRepository votazioneRepository, DelegheRepository delegheRepository, SocioRepository socioRepository, AssembleaState assembleaState, VotazioneState votazioneState) {
    this.assembleeRepository = assembleeRepository;
    this.assembleaService = assembleaService;
    this.votazioneRepository = votazioneRepository;
    this.delegheRepository = delegheRepository;
    this.socioRepository = socioRepository;
    this.assembleaState = assembleaState;
    this.votazioneState = votazioneState;
  }

  @ModelAttribute(name = "assembleaModel")
  public AssembleaEditModel assembleaModel() {
    return new AssembleaEditModel();
  }

  @GetMapping("/{id}")
  public String getAssemblea(Model model, @PathVariable("id") Long id, Principal principal) {
    var assemblea = assembleaService.getAssemblea(id);
    var votazioni = votazioneRepository.findAllByIdAssemblea(id);
    var idUtente = Long.parseLong(Utils.getKeycloakUserFromPrincipal(principal).getPreferredUsername());

    model.addAllAttributes(Map.of(
        "assemblea", assemblea,
        "votazioni", votazioni,
        "presenti", assembleaState.getPresenti(id),
        "isPresente", assembleaState.getPresenti(id).contains(idUtente),
        "hasDelega", delegheRepository.findDelegaByDeleganteAndIdAssemblea(idUtente, id).isPresent() ,
        "canStart", !assemblea.isInCorso() && assemblea.getIdProprietario().equals(idUtente),
        "canStop", assemblea.isInCorso() && assemblea.getIdProprietario().equals(idUtente),
        "votazioneState", votazioneState,
        "tessera", idUtente,
        "isProprietario", idUtente == assemblea.getIdProprietario() || (assemblea.getIdPresidente() != null && idUtente == assemblea.getIdPresidente())
    ));
    model.addAttribute("canTogglePresenza", assemblea.isInCorso() && votazioni.stream().allMatch(Votazione::isTerminata));
    model.addAttribute("canDelega", !assemblea.isNazionale());
    return "assemblee/view";
  }

  @GetMapping("/{id}/presenza")
  public String togglePresenza(@PathVariable("id") Long id, Principal principal) {
    var me = Long.parseLong(Utils.getKeycloakUserFromPrincipal(principal).getPreferredUsername());
    var delega = delegheRepository.findDelegaByDelegatoAndIdAssemblea(me, id);
    var assemblea = assembleeRepository.findById(id);
    var votazioni = votazioneRepository.findAllByIdAssemblea(id);
    if(votazioni.stream().anyMatch(x -> !x.isTerminata())) {
      throw new IllegalStateException("Non puoi segnarti come presente o assente durante una votazione");
    }
    if(assembleaState.getPresenti(id).contains(me)) {
      assembleaState.setAssente(id, me);
      delega.ifPresent(value -> assembleaState.setAssente(id, value.getDelegante()));
    } else {
      assembleaState.setPresente(id, me);
      delega.ifPresent(value -> assembleaState.setPresente(id, value.getDelegante()));
      if(assemblea.get().isRequire2FA()) {
        return "redirect:/assemblea/" + id + "/2fa";
      }
    }
    return "redirect:/assemblea/" + id;
  }

  @GetMapping("/{id}/2fa")
  public String get2fa(@PathVariable("id") Long id, Principal principal, Model model) {
    var me = Long.parseLong(Utils.getKeycloakUserFromPrincipal(principal).getPreferredUsername());
    model.addAttribute("key", qrImageUrl("Assemblea - " + me, assembleaState.get2faSecret(id, me)));
    model.addAttribute("assembleaId", id);
    return "assemblee/2fa";
  }

  @GetMapping("/{id}/presenti")
  public String getPresenti(@PathVariable("id") Long id, Model model, Principal principal) {
    var assemblea = assembleeRepository.findById(id);
    var me = Long.parseLong(Utils.getKeycloakUserFromPrincipal(principal).getPreferredUsername());
    var deleghe = delegheRepository.findAllByIdAssemblea(id).stream().collect(Collectors.toMap(Delega::getDelegante, Delega::getDelegato));
    model.addAttribute("partecipanti", Arrays.stream(assemblea.get().getPartecipanti())
        .map(x -> Map.entry(x, socioRepository.findById(x).map(y -> {
          if(assemblea.get().isNazionale())
            return y.getSezione() + " - " + y.getNome();
          else return y.getNome();
        }).orElse(x.toString())))
        .sorted(Map.Entry.comparingByValue())
        .collect(Collectors.toList()));
    model.addAttribute("presenti", assembleaState.getPresenti(id));
    model.addAttribute("assembleaId", id);
    model.addAttribute("deleghe", deleghe);
    model.addAttribute("isCovepo", Utils.isCovepo(assemblea, me));
    return "assemblee/presenti";
  }

  @GetMapping("/{id}/caccia/{idUtente}")
  public String kickPartecipante(@PathVariable("id") Long id, @PathVariable("idUtente") Long idUtente) {
    assembleaState.setAssente(id, idUtente);
    return "redirect:/assemblea/" + id + "/presenti";
  }

  @GetMapping("crea")
  public String getCreateAssemblea(Model model, Principal principal) {
    var me = Utils.getKeycloakUserFromPrincipal(principal);
    List<String> groups = (List<String>) me.getOtherClaims().get("groups");
    var sezione = groups.stream().filter(x -> x.matches("/[\\w\\s']+")).findFirst().map(x -> x.substring(1));
    if(sezione.isPresent()) {
      model.addAttribute("nomeSezione", sezione.get());
      var soci = socioRepository.findBySezione(sezione.get());
      model.addAttribute("soci", soci.stream().map(x -> String.valueOf(x.getId())).collect(Collectors.joining("\n")));
    }
    return "assemblee/create";
  }

  @PostMapping("/crea")
  public String createAssemblea(AssembleaEditModel assembleaModel, Principal principal) {
    var me = Long.parseLong(Utils.getKeycloakUserFromPrincipal(principal).getPreferredUsername());
    var newAssemblea = Assemblea.builder()
        .idProprietario(me)
        .nome(assembleaModel.getNome())
        .descrizione(assembleaModel.getDescrizione())
        .partecipanti(parsePartecipanti(assembleaModel.getPartecipanti()))
        .convocazione(LocalDateTime.parse(assembleaModel.getDateTime()))
        .stepOdg(0L)
        .odg(Arrays.stream(assembleaModel.getOdg().split("\n")).filter(x -> !x.isBlank()).toArray(String[]::new))
        .require2FA(assembleaModel.isRequire2fa())
        .isNazionale(assembleaModel.isNazionale())
        .build();

    assembleeRepository.save(newAssemblea);
    return "redirect:/";
  }
  private Long[] parsePartecipanti(String original) {
    return Arrays.stream(original.split("\n"))
        .map(String::trim)
        .filter(x -> Pattern.matches("\\d+", x))
        .map(Long::parseLong)
        .toArray(Long[]::new);
  }
}
