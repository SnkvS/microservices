package com.example.song;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SongService {
    private final SongRepository repository;
    public SongService(SongRepository repository) { this.repository = repository; }

    @Transactional
    public long create(SongDto dto) {
        Map<String, String> errors = SongValidation.validate(dto);
        if (!errors.isEmpty()) throw new SongValidationException(errors);
        if (repository.existsById(dto.id())) throw new SongConflictException(dto.id());
        repository.saveAndFlush(new SongEntity(dto));
        return dto.id();
    }

    public SongDto get(String rawId) {
        long id = SongValidation.id(rawId);
        return repository.findById(id).orElseThrow(() -> new SongNotFoundException(id)).toDto();
    }

    @Transactional
    public List<Long> delete(String rawIds) {
        List<Long> deleted = new ArrayList<>();
        for (long id : SongValidation.ids(rawIds)) {
            if (repository.existsById(id)) { repository.deleteById(id); deleted.add(id); }
        }
        return deleted;
    }
}
