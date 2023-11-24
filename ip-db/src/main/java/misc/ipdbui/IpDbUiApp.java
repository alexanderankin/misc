package misc.ipdbui;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import misc.ipdb.IpDbService;
import misc.ipdb.IpDbService.IpRange;
import misc.ipdb.IpDbService.IpSpace;
import misc.ipdb.util.DbFactory;
import misc.ipdb.util.IpDataNotFoundException;
import misc.ipdb.util.IpRangeConflictsException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@SpringBootApplication
class IpDbUiApp {
    public static void main(String[] args) {
        SpringApplication.run(IpDbUiApp.class, args);
    }

    @Configuration
    static class Config {
        @Bean
        IpDbService ipDbService(Optional<DbFactory> instance) {
            IpDbService ipDbService = new IpDbService(instance.orElse(DbFactory.INSTANCE));
            ipDbService.dbMigrator().migrate();
            return ipDbService;
        }
    }

    @RequiredArgsConstructor
    @RestController
    @RequestMapping("/api/v1")
    static class ApiRouter {
        private final IpDbService ipDbService;

        private static PageRequest toPageReq(Pageable p) {
            return PageRequest.of(p.getPageNumber(), p.getPageSize());
        }

        @GetMapping("/spaces")
        List<IpSpace> spaces(Pageable p) {
            return ipDbService.listSpaces(toPageReq(p));
        }

        @PostMapping("/spaces")
        IpSpace spaces(@RequestBody IpSpace ipSpace) {
            return ipDbService.create(ipSpace);
        }

        @GetMapping("/spaces/{id}")
        IpSpace getSpace(@PathVariable("id") int id) {
            return Optional.ofNullable(ipDbService.findSpace(id))
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        }

        @DeleteMapping("/spaces/{id}")
        IpSpace deleteSpace(@PathVariable("id") int id) {
            return Optional.ofNullable(ipDbService.findSpace(id)).map(ipDbService::delete)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        }

        @PutMapping("/spaces/{id}")
        IpSpace updateSpace(@PathVariable("id") int id, @RequestBody IpSpace ipSpace) {
            return Optional.ofNullable(ipDbService.update(ipSpace.setId(id)))
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        }

        @GetMapping("/spaces/{id}/ranges")
        List<IpRangeDto> ranges(@PathVariable("id") int id, Pageable p) {
            return ipDbService.listRanges(getSpace(id), toPageReq(p)).stream().map(IpRangeDto::from).toList();
        }

        @GetMapping("/spaces/{id}/ranges/{rangeId}")
        IpRange range(@PathVariable("id") int id, @PathVariable("rangeId") int rangeId) {
            try {
                return ipDbService.findRange(id, rangeId);
            } catch (IpDataNotFoundException e) {
                throw new ResponseStatusException(NOT_FOUND);
            } catch (IpRangeConflictsException | DataIntegrityViolationException e) {
                throw new ResponseStatusException(BAD_REQUEST);
            }
        }

        @PostMapping("/spaces/{id}/ranges")
        IpRangeDto ranges(@PathVariable("id") int id, @RequestBody IpRangeDto ipRange) {
            ipRange.setIpSpaceId(id);
            try {
                return IpRangeDto.from(ipDbService.reserve(ipRange.toIpRange(), ipRange.getMin(), ipRange.getMax()));
            } catch (IpDataNotFoundException e) {
                throw new ResponseStatusException(NOT_FOUND);
            } catch (IpRangeConflictsException | DataIntegrityViolationException e) {
                throw new ResponseStatusException(BAD_REQUEST);
            }
        }

        @DeleteMapping("/spaces/{id}/ranges/{rangeId}")
        IpRangeDto ranges(@PathVariable("id") int id, @PathVariable("rangeId") int rangeId) {
            try {
                return IpRangeDto.from(ipDbService.release(range(id, rangeId)));
            } catch (IpDataNotFoundException e) {
                throw new ResponseStatusException(NOT_FOUND);
            } catch (IpRangeConflictsException | DataIntegrityViolationException e) {
                throw new ResponseStatusException(BAD_REQUEST);
            }
        }

        @Data
        @Accessors(chain = true)
        public static class IpRangeDto {
            Integer id;
            Integer ipSpaceId;
            String name;
            String description;
            String min;
            String max;

            static IpRangeDto from(IpRange ipRange) {
                return new IpRangeDto()
                        .setId(ipRange.getId())
                        .setIpSpaceId(ipRange.getIpSpaceId())
                        .setName(ipRange.getName())
                        .setDescription(ipRange.getDescription())
                        .setMin(IpDbService.IpAddress.from(ipRange.getMin(), ipRange.getIpSpace().getIpVersion()).address())
                        .setMax(IpDbService.IpAddress.from(ipRange.getMax(), ipRange.getIpSpace().getIpVersion()).address())
                        ;
            }

            IpRange toIpRange() {
                return new IpRange()
                        .setId(id)
                        .setIpSpaceId(ipSpaceId)
                        .setName(name)
                        .setDescription(description);
            }
        }

    }
}
